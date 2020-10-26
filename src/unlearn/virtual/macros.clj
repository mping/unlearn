(ns unlearn.virtual.macros
  (:require [clojure.set :as set]
            [unlearn.virtual.executor :as executor]
            [riddley.walk :as walk]
            [riddley.compiler :as compiler]
            [manifold.deferred :as d]
            [criterium.core :as cc])
  (:import (java.util.concurrent ExecutorService)
           (clojure.lang Fn)))


(defn whom-am-i []
  (str (.getName (Thread/currentThread))))

;;;;
;; public stuff

(defprotocol ITask (run-task [this]))

(extend-protocol ITask
  ;; sets, maps and keywords implement ifn? which means they would be
  ;; treated as tasks but they always need an argument
  Fn (run-task [f] (f))
  Object (run-task [f] f)
  nil (run-task [_] nil))


(defmacro task=> [& body]
  "Creates a task out of a body"
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
   (let [deadline? (= ::executor/deadline (first body))
         deadline  (when deadline? (nth body 1))
         executor? (= ::executor/executor (first body))
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

;(split-tasks-opts '(::executor/executor 2  ::executor/deadline 1 (+ 1 1)))

(defmacro all
  "Runs each form within its own virtual thread."
  ([& body]
   (let [[tasks ex-opts] (split-tasks-opts body)]
     `(let [tasks# ~(tasks-of tasks)]
        (with-open [^ExecutorService e# (executor/executor ~ex-opts)]
          (->> (.invokeAll e# tasks#)
               (mapv #(.get %))))))))

(defmacro any
  "Runs each form within its own virtual thread, returning the first to finish"
  ([& body]
   (let [[tasks ex-opts] (split-tasks-opts body)]
     `(let [tasks# ~(tasks-of tasks)]
        (with-open [^ExecutorService e# (executor/executor ~ex-opts)]
          (.invokeAny e# tasks#))))))

(defmacro task
  "Runs a single form in its own virtual thread."
  [& body]
  (let [[task ex-opts] (split-tasks-opts body)]
    `(with-open [^ExecutorService e# (executor/executor ~ex-opts)]
       @(.submitTask e# (cast Callable (^:once fn [] (run-task ~@task)))))))

(defn whoami []
  (let [n (.getName (Thread/currentThread))]
    (println n)
    n))

#_(all
    (fn [] (whoami) :first)
    (do :second)
    (task (whoami))
    (task nil)
    (do :do)
    (task=> (whoami) (+ 1 2)))



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
(defn- expand-let-flow [bindings orig-body]
  (let [[body ex-opts] orig-body
        [_ bindings & body] (walk/macroexpand-all `(let ~bindings ~@body))
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
    `(let [~custom-ex (executor/executor ~ex-opts)]          ;; TODO receive opts
       (let [~@(mapcat
                 (fn [n var val gensym]
                   (let [deps (gensym->deps gensym)]
                     (if (empty? deps)
                       (when (dep? gensym)
                         [gensym val])
                       [gensym
                        `(->> [(all ~@deps :executor ~custom-ex)]
                              (apply (fn [[~@(map gensym->var deps)]]
                                       ~val)))])))
                 (range)
                 vars'
                 vals'
                 gensyms)]
         (->> [(all ~@body-dep? :executor ~custom-ex)]
              (apply (fn [[~@(map gensym->var body-dep?)]]
                       ~@body)))))))

(defmacro dag
  "Constructs a DAG of let-bindings and executes it"
  [bindings & body]
  (expand-let-flow
    bindings
    body))


(time
  (dag [a (task=> (Thread/sleep 100) (+ 1 1))
        b (do     (Thread/sleep 100) 2)
        c (task=> (println "c" (whom-am-i)) (+ 1 a))]
       (+ a b b)))

(walk/macroexpand '(dag [a (task=> (Thread/sleep 100) (+ 1 1))
                         b (do  (Thread/sleep 100) 2)
                         c (task=> (println "c" (whom-am-i)) (+ 1 a))]
                        (+ a b)))
