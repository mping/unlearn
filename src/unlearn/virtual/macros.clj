(ns unlearn.virtual.macros
  (:require [clojure.set :as set]
            [unlearn.virtual.executor :as executor]
            [riddley.walk :as walk]
            [riddley.compiler :as compiler])
  (:import (java.util.concurrent ExecutorService)
           (clojure.lang Fn)))

;;;;
;; public stuff

(defprotocol ITask (run-task [this]))

(extend-protocol ITask
  ;; sets, maps and keywords implement ifn? which means they would be
  ;; treated as tasks but they always need an argument
  Fn (run-task [f] (f))
  Object (run-task [f] f)
  nil (run-task [_] nil))

(defmacro task=>
  "Creates a task out of a body"
  [& body]
  ;; memoization allows a task to be called several times and just like a deferred,
  ;; only return the success value
  `(memoize (fn ^:once [] ~@body)))

;;;;
;; macro helpers

(defn- tasks-of
  "Helper fn for `with-executor` macro"
  [bindings]
  (mapv (fn [binding] `(^:once fn [] (run-task ~binding)))
        bindings))

(defn- split-tasks-opts
  ([body]
   (split-tasks-opts body {}))
  ([body curr-opts]
   (let [deadline? (= :deadline (first body))
         deadline  (when deadline? (nth body 1))
         executor? (= :executor (first body))
         executor  (when executor? (nth body 1))
         ex-opts   (cond-> curr-opts
                           deadline? (merge {:deadline deadline})
                           executor? (merge {:executor executor}))
         tasks     (if (or deadline? executor?)
                     (rest (rest body))
                     body)]
     (if (not (or deadline? executor?))
       [tasks (merge curr-opts ex-opts)]
       (split-tasks-opts tasks ex-opts)))))

;; (split-tasks-opts '(:executor 2  :deadline 1 (+ 1 1)))
;; [((+ 1 1)) {:executor 2, :deadline 1}]


(defmacro parallel
  "Runs each form within its own virtual thread."
  ([& body]
   (let [[tasks ex-opts] (split-tasks-opts body)
         block-symbol (if (:executor ex-opts) 'let 'with-open)]
     `(let [tasks# ~(tasks-of tasks)]
        (~block-symbol [^ExecutorService e# (executor/executor ~ex-opts)]
          (->> (.invokeAll e# tasks#)
               (mapv #(.get %))))))))


(comment
  (macroexpand-1 '(parallel :executor x (+ 1 1))))

(defmacro race
  "Runs each form within its own virtual thread, returning the first to finish"
  ([& body]
   (let [[tasks ex-opts] (split-tasks-opts body)
         block-symbol (if (:executor ex-opts) 'let 'with-open)]
     `(let [tasks# ~(tasks-of tasks)]
        (~block-symbol [^ExecutorService e# (executor/executor ~ex-opts)]
          (.invokeAny e# tasks#))))))

(defmacro task
  "Runs a single form in its own virtual thread."
  [& body]
  (let [[task ex-opts] (split-tasks-opts body)
        block-symbol (if (:executor ex-opts) 'let 'with-open)]
    `(~block-symbol [^ExecutorService e# (executor/executor ~ex-opts)]
       @(.submitTask e# (cast Callable (^:once fn [] (run-task ~@task)))))))

;; shamelessly copied from manifold.deferred/back-references
(defn- back-references [marker form]
  (let [syms (atom #{})]
    (walk/walk-exprs
      symbol?
      (fn [s]
        (when (some-> (compiler/locals) (find s) key meta (get marker))
          (swap! syms conj s)))
      form)
    @syms))


;; shamelessly copied from manifold.deferred/expand-let-flow
(defn- expand-let [bindings [body] ex-opts]

  (let [block-symbol (if (:executor ex-opts) 'let 'with-open)
        flattened-opts (-> (into [] ex-opts) flatten)

        [_ bindings & body] (walk/macroexpand-all `(let ~bindings ~body))
        locals       (keys (compiler/locals))
        vars         (->> bindings (partition 2) (map first))
        custom-ex    (gensym "custom-executor")
        marker       (gensym)
        vars'        (->> vars (concat locals) (map #(vary-meta % assoc marker true)))
        gensyms      (repeatedly (count vars') gensym)
        gensym->var  (zipmap gensyms vars')
        vals'        (->> bindings (partition 2) (map second) (concat locals))
        gensym->deps (zipmap
                       gensyms
                       (->> (count vars')
                            range
                            (map
                              (fn [n]
                                `(let [~@(interleave (take n vars') (repeat nil))
                                       ~(nth vars' n) ~(nth vals' n)])))
                            (map
                              (fn [n form]
                                (map
                                  (zipmap vars' (take n gensyms))
                                  (back-references marker form)))
                              (range))))
        binding-dep? (->> gensym->deps vals (apply concat) set)

        body-dep?    (->> `(let [~@(interleave
                                     vars'
                                     (repeat nil))]
                             ~@body)
                          (back-references marker)
                          (map (zipmap vars' gensyms))
                          (concat (drop (count vars) gensyms))
                          set)
        dep?         (set/union binding-dep? body-dep?)]
    `(~block-symbol [~custom-ex (executor/executor ~ex-opts)]
       (let [~@(mapcat
                 (fn [n var val gensym]
                   ;; use delay to defer execution up until the very last step
                   (let [deps (gensym->deps gensym)]
                     (if (empty? deps)
                       (when (dep? gensym)
                         [gensym `(delay ~val)])
                       [gensym
                        ;; ensure all delay'ed deps are resolved
                        `(delay (->> [(parallel ~@flattened-opts (deref ~@deps))] ;; :executor ~custom-ex
                                     (apply (fn [[~@(map gensym->var deps)]]
                                              ~val))))])))
                 (range)
                 vars'
                 vals'
                 gensyms)]

         ;; ensure all delay'ed deps are resolved
         (->> [(parallel ~@flattened-opts ~@(for [d body-dep?] `@~d) #_~@body-dep?)] ;; :executor ~custom-ex
              (apply (fn [[~@(map gensym->var body-dep?)]]
                       ~@body)))))))


(defmacro schedule
  "Sequences a DAG of let-bindings and executes it"
  [& body]
  (let [[tasks ex-opts] (split-tasks-opts body)
        [bindings & bbody] tasks]
    (expand-let bindings bbody ex-opts)))

;;;
;; examples

(defn whoami []
  (let [n (.getName (Thread/currentThread))]
    (println n)
    n))

(time
  (schedule [a (task=> (Thread/sleep 100) (+ 1 1))
             b (task=> (Thread/sleep 100) 2)
             c (race (Thread/sleep 10)
                     (Thread/sleep 10)
                     1)]
            [a b c]))

;; 100ms because a and b can be run independently

(defn log [steps]
  (dotimes [i steps]
    (Thread/sleep ^long (rand-int 100))
    (println (str (.getName (Thread/currentThread)) "Hello: " i)))
  steps)

(race (log 10)
      (log 9))

(parallel
  (fn [] (whoami) :first)
  :second
  (constantly 3)
  (task (whoami))
  (task nil)
  (do :do)
  (task=> (whoami) (+ 1 2)))
