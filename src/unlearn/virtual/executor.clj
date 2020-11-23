(ns unlearn.virtual.executor
  (:require [clojure.tools.logging :as log])
  (:import (java.util.concurrent ExecutorService Executors Future)
           (java.time Instant)))

(declare global-uncaught-exception-handler)
(defn thread-factory
  "Makes a virtual thread factory"
  ([]
   (thread-factory nil))
  ([{:keys [prefix exception-handler ^ExecutorService scheduler]
     :or   {prefix            "unlearn.virtual"
            exception-handler global-uncaught-exception-handler}}]
   (let [builder (Thread/builder)]
     (-> (if scheduler
           (.virtual builder scheduler)
           (.virtual builder))
         (.name prefix 0)
         (.uncaughtExceptionHandler exception-handler)
         (.factory)))))

(defn executor
  "Makes a virtual executor"
  ([] (executor nil))
  ([{:keys [^Instant deadline thread-factory executor]
     :or   {thread-factory (thread-factory)}}]
   ;; exceptions are not propagated to uncaght handler, see
   ;; https://stackoverflow.com/questions/2248131/handling-exceptions-from-java-executorservice-tasks
   (let [ex (or executor (Executors/newThreadExecutor thread-factory))]
     (cond-> ex
             (some? deadline) (.withDeadline deadline)))))

(defn ^Future submit
  "Reflection-friendly executor submit"
  [executor callable]
  (.submit ^ExecutorService executor ^Callable callable))

;;;;
;; override clojure defaults

(defn set-core-agent-executors-virtual!
  "Overrides the clojure.core agent executors to use the `base-executor`."
  []
  (let [factory  (thread-factory {:prefix "clojure-agent-send-off-virtual-pool"})
        executor (executor {:thread-factory factory})]
    (set-agent-send-executor! executor)
    (set-agent-send-off-executor! executor)))

(def ^:private global-uncaught-exception-handler
  (reify Thread$UncaughtExceptionHandler
    (^void uncaughtException [_ ^Thread t ^Throwable ex]
      (log/error ex "Uncaught exception on" (.getName t)))))

(defn set-default-uncaught-exception-handler! []
  (Thread/setDefaultUncaughtExceptionHandler global-uncaught-exception-handler))

(comment
  (set-core-agent-executors-virtual!)
  (future (println (.getName (Thread/currentThread)) (/ 1 0))))