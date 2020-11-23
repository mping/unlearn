(ns unlearn.virtual.core
  (:refer-clojure :exclude [pmap])
  (:require [unlearn.virtual.executor :as executor]
            [manifold.deferred])
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
  [f coll {:keys [deadline] :as _opts}]
  (with-open [^ExecutorService e (executor/executor (when deadline {:deadline deadline}))]
    (let [tasks (into [] (map (fn [el] (^:once fn [] (f el))) coll))
          tasks (.submitTasks e tasks)]
      (tasks->join tasks))))


(defn- tasks-of
  "Helper fn for `with-executor` macro"
  [bindings]
  (mapv (fn [binding] `(^:once fn [] ~binding))
        bindings))

(defn- split-tasks-opts [body]
  (let [deadline? (= :deadline (first body))
        deadline  (when deadline? (nth body 1))
        ex-opts   (when deadline? {:deadline deadline})
        tasks     (if deadline? (rest (rest body)) body)]
    [tasks ex-opts]))

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

(defmacro single
  "Runs a single form in its own virtual thread."
  [& body]
  (let [[task ex-opts] (split-tasks-opts body)]
    `(with-open [^ExecutorService e# (executor/executor ~ex-opts)]
       @(.submitTask e# (cast Callable (^:once fn [] (do ~@task)))))))
