(ns unlearn.virtual.macros
  (:require [clojure.set :as set]
            [unlearn.virtual.executor :as executor]
            [riddley.walk :as walk]
            [riddley.compiler :as compiler])
  (:import (java.util.concurrent ExecutorService)
           (clojure.lang Fn)))

;;;;
;; current executor
;;;;

(def ^{:dynamic true :private true :tag ExecutorService} *executor* nil)

(defn- decide-executor [{:keys [executor deadline] :as opts}]
  (cond (and executor deadline)
        (throw (IllegalArgumentException. "Cannot have both :executor and :deadline"))
        (some? executor) executor
        (some? deadline) `(executor/executor ~opts)))

(defmacro ^:private with-executor [opts & body]
  `(with-bindings {*executor* ~(decide-executor opts)}
     (do ~@body)))

;;;;
;; public stuff

(defprotocol ITask
  (run-task [this]))

(extend-protocol ITask
  ;; sets, maps and keywords implement ifn? which means they would be
  ;; treated as tasks but they always need an argument
  Fn (run-task [f] (f))
  Object (run-task [f] f)
  nil (run-task [_] nil))

(defmacro task
  "Creates a task out of a body"
  [& body]
  `(fn [] (do ~@body)))

;;;;
;; macro helpers

(defn- tasks-of
  "Helper fn for `with-executor` macro"
  [bindings]
  (mapv (fn [binding] `(fn [] (run-task ~binding)))
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

;; TODO use a generic (with-executor ex-opts ...) macro
;; TODO ensure that if :deadline is present, the macro needs to create a new executor even if
;; one is supplied

(defmacro parallel
  "Runs each form within its own virtual thread."
  ([& body]
   (let [[tasks ex-opts] (split-tasks-opts body)
         block-symbol (if (:executor ex-opts) 'let 'with-open)]
     `(let [tasks# ~(tasks-of tasks)]
        (~block-symbol [^ExecutorService e# (executor/executor ~ex-opts)]
          (->> (.invokeAll e# tasks#)
               (mapv #(.get %))))))))

(defmacro race
  "Runs each form within its own virtual thread, returning the first to finish"
  ([& body]
   (let [[tasks ex-opts] (split-tasks-opts body)
         block-symbol (if (:executor ex-opts) 'let 'with-open)]
     `(let [tasks# ~(tasks-of tasks)]
        (~block-symbol [^ExecutorService e# (executor/executor ~ex-opts)]
          (.invokeAny e# tasks#))))))

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
  (let [block-symbol   (if (:executor ex-opts) 'let 'with-open)
        flattened-opts (-> (into [] ex-opts) flatten)

        [_ bindings & body] (walk/macroexpand-all `(let ~bindings ~body))
        locals         (keys (compiler/locals))
        vars           (->> bindings (partition 2) (map first))
        custom-ex      (gensym "custom-executor")
        marker         (gensym)
        vars'          (->> vars (concat locals) (map #(vary-meta % assoc marker true)))
        gensyms        (repeatedly (count vars') gensym)
        gensym->var    (zipmap gensyms vars')
        vals'          (->> bindings (partition 2) (map second) (concat locals))
        gensym->deps   (zipmap
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
        binding-dep?   (->> gensym->deps vals (apply concat) set)

        body-dep?      (->> `(let [~@(interleave
                                       vars'
                                       (repeat nil))]
                               ~@body)
                            (back-references marker)
                            (map (zipmap vars' gensyms))
                            (concat (drop (count vars) gensyms))
                            set)
        dep?           (set/union binding-dep? body-dep?)]
    `(~block-symbol [~custom-ex (executor/executor ~ex-opts)]
       (let [~@(mapcat
                 (fn [_n _var val gensym]
                   ;; use delay to defer execution up until the very last step
                   (let [deps (gensym->deps gensym)]
                     (if (empty? deps)
                       (when (dep? gensym)
                         [gensym `(executor/submit ~custom-ex (fn [] (run-task ~val)))])
                       [gensym
                        `(executor/submit ~custom-ex ;; :executor ~custom-ex
                                          (fn []
                                            (apply (fn [[~@(map gensym->var deps)]]
                                                     ;; TODO: because val is a task
                                                     (run-task ~val))
                                                   [[~@(for [d deps] `@~d)]])))])))
                 (range)
                 vars'
                 vals'
                 gensyms)]

         ;; ensure all delay'ed deps are resolved
         ;; :executor ~custom-ex
         (let [[~@(map gensym->var body-dep?)] [~@(for [d body-dep?] `@~d)]]
           ~@body)))))

(defmacro schedule
  "Sequences a DAG of let-bindings and executes it"
  [& body]
  (let [[tasks ex-opts] (split-tasks-opts body)
        [bindings & bbody] tasks]
    (expand-let bindings bbody ex-opts)))
