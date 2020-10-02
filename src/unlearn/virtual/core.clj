(ns unlearn.virtual.core
  (:refer-clojure :exclude [pmap])
  (:require [unlearn.virtual.executor :as executor])
  (:import (java.util.concurrent ExecutorService CompletableFuture)
           (java.util Collection)))

;;;;
;; internal fns

(defn- tasks->join
  "Waits for all the tasks to finish, returning a seq with the results"
  [^Collection tasks]
  ;; TODO: ensure the tasks are in order
  (->> (CompletableFuture/completed tasks)
       (.iterator)
       (iterator-seq)
       (map #(.join %))))

;;;;
;; nice things

(defn pmap
  "Like pmap, but using virtual threads"
  [f coll]
  (with-open [^ExecutorService e (executor/executor)]
    (let [tasks (into [] (map (fn [el] (^:once fn [] (f el))) coll))
          tasks (.submitTasks e tasks)]
      (tasks->join tasks))))

(defn- tasks-of
  "Helper fn for `with-executor` macro"
  [bindings]
  (mapv (fn [binding] `(^:once fn [] ~binding))
        bindings))

(defmacro with-executor
  "Runs each form within its own virtual thread."
  [^ExecutorService ex & body]
  `(let [tasks# ~(tasks-of body)]
     (with-open [^ExecutorService e# ~ex]
       (let [submitted# (.submitTasks e# tasks#)]
         (tasks->join submitted#)))))

(defmacro with-execute [& body]
  `(with-executor (executor/executor) ~@body))


(comment
  ;; runs each (Thread/currentThread) in a vthread
  (with-executor (executor/executor)
    (Thread/currentThread)
    (Thread/currentThread))

  ;; crazy: destructure
  (let [[r1 r2] (with-execute (+ 1 1) (+ 2 2))]
    (+ r1 r2))

  ;; parallel map
  (pmap (fn [_] (Thread/sleep 1000)) (take 1e3 (range)))) nil
