(ns unlearn.virtual.macros
  (:require [riddley.compiler :as compiler]
            [riddley.walk :as walk]
            [clojure.set :as set]
            [unlearn.virtual.executor :as executor]
            [manifold.deferred :as d]
            [criterium.core :as cc])
  (:import (java.util.concurrent ExecutorService CompletableFuture)
           (java.util.function Function Supplier)
           (java.lang AutoCloseable)))

(defmacro task [& body]
  ;; todo submit to executor
  `(fn [] ~@body))

(defn- vars-of [binding-pairs]
  (let [[vars sexprs] (apply map list binding-pairs)]
    vars))

(defn- bindings->nested-tasks
  "Returns a list of `Function` that will be sequentially run with the previous function's result.
  (bindings-nested-tasks '(a 1
                           b (+ 2 a)
                           c (+ a b))
  In pseudocode:
  [(fn [] [1])
   (fn [[a]] (+ 2 a)
   (fn [[a b]] (+ a b)]
  "
  [binding-pairs]
  (let [vars (vars-of binding-pairs)]
    (->> (map-indexed (fn [i [v sexpr]]
                        (let [first? (= i 0)
                              last?  (= i (dec (count binding-pairs)))
                              fargs  (if first?
                                       '_
                                       (into [] (take i vars)))
                              fbody  (if last? sexpr (into [] (concat (take i vars) [sexpr])))]

                          `[(reify Function
                              (~'java.util.function.Function/apply [~'this ~fargs] ~fbody))]))

                      binding-pairs)
         (apply concat)
         (into []))))

(defmacro ^:private sequentially
  "Runs each task sequentially, binding each task to a var, similar to `let`.
  A task can be a value or the result of `(task ...)`.
  Each task can depend on a previous value"
  {:forms '[(sequentially [tasks*] exprs*)]}
  [bindings body]
  (let [ex            (gensym)
        b             (gensym)
        binding-pairs (partition 2 (concat bindings [b body]))
        task-fns      (bindings->nested-tasks binding-pairs)]

    `(let [~(with-meta ex {:tag `ExecutorService}) (executor/executor)]
       (try
         ;; effectively a kind of reduce
         @(.. (CompletableFuture/completedFuture nil)
              ~@(for [f task-fns]
                  `(~'thenApplyAsync ~(with-meta f {:tag Function}) ~ex)))
         (finally
           (.close ~ex))))))

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
              (+ a b c))))
;
;Evaluation count : 2092920 in 60 samples of 34882 calls.
;Execution time mean : 30,245814 µs
;Execution time std-deviation : 3,084815 µs
;Execution time lower quantile : 24,993762 µs ( 2,5%)
;Execution time upper quantile : 34,435957 µs (97,5%)
;Overhead used : 4,146448 ns


(comment
  (parallel [a 1
             b 2
             c 3]))