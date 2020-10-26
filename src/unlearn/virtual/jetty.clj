(ns unlearn.virtual.jetty
  (:require [unlearn.virtual.executor :as executor])
  (:import (java.util.concurrent ExecutorService TimeUnit)
           (org.eclipse.jetty.util.component AbstractLifeCycle)
           (org.eclipse.jetty.util.thread ThreadPool QueuedThreadPool)))

(defn thread-pool
  "Makes an unbounded thread pool backed by a virtual thread factory"
  ([]
   (thread-pool nil))
  ([{:keys [stop-timeout stop-units] :or {stop-timeout 60000 stop-units TimeUnit/MILLISECONDS}}]
   (let [executor ^ExecutorService (executor/executor
                                     (executor/thread-factory {:prefix "jetty-pool"}))
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
            thread-factory   (executor/thread-factory)
            daemon?          false}}]
   (doto (QueuedThreadPool. (int max-threads)
                            (int min-threads)
                            (int idle-timeout)
                            (int reserved-threads)
                            queue
                            thread-group
                            thread-factory)
     (.setDaemon daemon?))))
