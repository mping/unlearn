(ns unlearn.virtual.macros
  (:require [riddley.compiler :as compiler]
            [riddley.walk :as walk]
            [clojure.set :as set]
            [unlearn.virtual.executor :as executor]
            [manifold.deferred :as d]
            [criterium.core :as cc])
  (:import (java.util.concurrent ExecutorService CompletableFuture)
           (java.util.function Function Supplier)))

(defmacro task [& body]
  ;; todo submit to executor
  `(fn [] ~@body))


(defn- vars-of [binding-pairs]
  (let [[vars sexprs] (apply map list binding-pairs)]
    vars))

(defn- bindings->tasks [ex binding-pairs]
  (let [vars        (vars-of binding-pairs)
        derefs-list (map (fn [v] `(deref ~(symbol (str "fut-" v)))) vars)]
    (->> (map-indexed (fn [i [v sexpr]]
                        ;; creates a (let [fut-var1 (.submit ... ((partial (fn [args]) (deref arg0))
                        `[~(symbol (str "fut-" v))

                          (.submit ~ex
                                   ^"Callable"
                                   (reify Callable
                                     (~'call [~'this]
                                       ;; (let [a @fut-a ... n @fut-n] (sexpr-body))
                                       (let [~@(take (* 2 i) (interleave vars derefs-list))]
                                          ~sexpr))))])
                      binding-pairs)
         (apply concat)
         (into [derefs-list]))))


(defmacro ^:private sequentially
  "Runs each task sequentially, binding each task to a var, similar to `let`.
  A task can be a value or the result of `(task ...)`.
  Each task can depend on a previous value"
  {:forms '[(sequentially [tasks*] exprs*)]}
  [bindings body]
  (let [binding-pairs (partition 2 bindings)
        vars          (vars-of binding-pairs)
        ex            (gensym)
        [derefs-list & bindings-tasks-pairs] (bindings->tasks ex binding-pairs)]
    `(let [~(with-meta ex {:tag ExecutorService}) (executor/executor)]
       (try
          (let [~@bindings-tasks-pairs]
            (let [~@(interleave vars derefs-list)]
              ~body))
          (finally (.close ~(with-meta ex {:tag ExecutorService})))))))

(clojure.pprint/pprint
  (macroexpand-1
    '(sequentially [a 1
                    b (+ a 1)
                    c (+ b a)]
       (+ a b c))))

(sequentially [a 1
               b (+ a 1)
               c (+ b a)]
  (+ a b c))


(comment
  (set! *warn-on-reflection* true)
  (set! *print-meta* true)
  (cc/bench @(d/let-flow [a (d/future 1)
                          b (d/future (+ a 1))
                          c (d/future (+ a b))]
               (+ a b c)))

  ;Evaluation count : 3183720 in 60 samples of 53062 calls.
  ;Execution time mean : 18,571357 µs
  ;Execution time std-deviation : 1,314774 µs
  ;Execution time lower quantile : 17,121132 µs ( 2,5%)
  ;Execution time upper quantile : 21,454793 µs (97,5%)
  ;Overhead used : 4,146448 ns

  (cc/bench (sequentially [a 1
                           b (+ a 1)
                           c (+ b a)]
              (+ a b c)))
  ;
  ;Evaluation count : 2092920 in 60 samples of 34882 calls.
  ;Execution time mean : 30,245814 µs
  ;Execution time std-deviation : 3,084815 µs
  ;Execution time lower quantile : 24,993762 µs ( 2,5%)
  ;Execution time upper quantile : 34,435957 µs (97,5%)
  ;Overhead used : 4,146448 ns


  (cc/quick-bench
    @(let [^ExecutorService ex (unlearn.virtual.executor/executor)]
       (.. (CompletableFuture/completedFuture 1)
           (thenComposeAsync
             ;; b
             (reify Function
               (java.util.function.Function/apply [this a]
                 (CompletableFuture/completedFuture [a (+ a 1)]))))
           (thenComposeAsync
             ;; c
             (reify Function
               (java.util.function.Function/apply [this [a b]]
                 (CompletableFuture/completedFuture [a b (+ a b)]))))
           (thenComposeAsync
             ;; res
             (reify Function
               (java.util.function.Function/apply [this [a b c]]
                 (CompletableFuture/completedFuture (+ a b c)))))))))




(comment
  (parallel [a 1
             b 2
             c 3]))