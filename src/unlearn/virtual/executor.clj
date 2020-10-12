(ns unlearn.virtual.executor
  (:require [clojure.tools.logging :as log])
  (:import (java.util.concurrent TimeUnit ExecutorService Executors)
           (org.eclipse.jetty.util.component AbstractLifeCycle)
           (org.eclipse.jetty.util.thread ThreadPool QueuedThreadPool)
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
  ([] (executor nil))
  ([{:keys [^ExecutorService scheduler
            ^Instant deadline
            prefix
            exception-handler] :as opts}]
   (let [ex (Executors/newThreadExecutor (thread-factory opts))]
     (cond-> ex (some? deadline) (.withDeadline deadline)))))

(defn virtual-executor
  ([]
   (virtual-executor nil))
  ([^Instant deadline]
   (cond-> (Executors/newVirtualThreadExecutor)
     deadline (.withDeadline deadline))))

(defn periodically
  [f interval]
  (doto (Thread.
          #(try
             (while (not (.isInterrupted (Thread/currentThread)))
               (Thread/sleep interval)
               (f))
             (catch InterruptedException _)))
    (.start)))

(defn thread-pool
  "Makes an unbounded thread pool backed by a virtual thread factory"
  ([]
   (thread-pool nil))
  ([{:keys [stop-timeout stop-units] :or {stop-timeout 60000 stop-units TimeUnit/MILLISECONDS}}]
   (let [executor ^ExecutorService (executor {:prefix "virtual"})
         lock     (Object.)]
     (proxy
       [AbstractLifeCycle ThreadPool]
       []
       (doStart [])
       (doStop []
         (.awaitTermination executor stop-timeout stop-units))
       (execute [^Runnable task]
         (.submit executor task))
       (join []
         (do (locking lock
               (while (.isRunning this)
                 (.wait lock)))
             (while (.isStopping this)
               (Thread/sleep 1))))))))


(defn queued-thread-pool
  "Makes a queued thread pool backed by a virtual thread factory"
  ([]
   (queued-thread-pool nil))
  ([{:keys [min-threads max-threads idle-timeout reserved-threads queue thread-group thread-factory daemon?]
     :or   {min-threads      8
            max-threads      200
            idle-timeout     60000
            reserved-threads -1
            queue            nil
            thread-group     nil
            thread-factory   (thread-factory)
            daemon?          false}}]
   (doto (QueuedThreadPool. (int max-threads)
                            (int min-threads)
                            (int idle-timeout)
                            (int reserved-threads)
                            queue
                            thread-group
                            thread-factory)
     (.setDaemon daemon?))))
;;;;
;; override clojure defaults

(defn set-core-agent-executors-virtual!
  "Overrides the clojure.core agent executors to use the `base-executor`."
  []
  (let [executor (executor)]
    (set-agent-send-executor! executor)
    (set-agent-send-off-executor! executor)))

(def ^:private global-uncaught-exception-handler
  (reify Thread$UncaughtExceptionHandler
    (^void uncaughtException [_ ^Thread t ^Throwable ex]
      (log/error ex "Uncaught exception on" (.getName t)))))

(defn set-default-uncaught-exception-handler! []
  (Thread/setDefaultUncaughtExceptionHandler global-uncaught-exception-handler))
