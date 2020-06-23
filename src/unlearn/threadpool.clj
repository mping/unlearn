(ns unlearn.threadpool
  (:require [clojure.tools.logging :as log])
  (:import (java.util.concurrent Executors TimeUnit ExecutorService)
           (org.eclipse.jetty.util.component AbstractLifeCycle)
           (org.eclipse.jetty.util.thread ThreadPool QueuedThreadPool)
           (java.lang Thread$UncaughtExceptionHandler)))

(def global-uncaught-exception-handler
  (reify Thread$UncaughtExceptionHandler
    (^void uncaughtException [_ ^Thread t ^Throwable ex]
      (log/error ex "Uncaught exception on" (.getName t)))))

(defn set-default-global-uncaught-exception-handler! []
  (Thread/setDefaultUncaughtExceptionHandler global-uncaught-exception-handler))

(defn make-virtual-thread-factory
  "Makes a virtual thread factory"
  ([]
   (make-virtual-thread-factory nil))
  ([{:keys [name exception-handler]
     :or   {name              "unlearn.virtual"
            exception-handler global-uncaught-exception-handler}}]
   (-> (Thread/builder)
       (.virtual)
       (.name name 0)
       (.uncaughtExceptionHandler global-uncaught-exception-handler)
       (.factory))))

(defn make-unbounded-executor []
  (Executors/newUnboundedExecutor (make-virtual-thread-factory)))

(defn make-unbounded-thread-pool
  "Makes an unbounded thread pool backed by a virtual thread factory"
  ([]
   (make-unbounded-thread-pool nil))
  ([{:keys [stop-timeout stop-units] :or {stop-timeout 60 stop-units TimeUnit/SECONDS}}]
   (let [executor ^ExecutorService (make-unbounded-executor)
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


(defn make-queued-thread-pool
  "Makes a queued thread pool backed by a virtual thread factory"
  ([]
   (make-queued-thread-pool nil))
  ([{:keys [min-threads max-threads idle-timeout reserved-threads queue thread-group thread-factory daemon?]
     :or   {min-threads      8
            max-threads      200
            idle-timeout     60000
            reserved-threads -1
            queue            nil
            thread-group     nil
            thread-factory   (make-virtual-thread-factory)
            daemon?          false}}]
   (doto (QueuedThreadPool. (int max-threads)
                            (int min-threads)
                            (int idle-timeout)
                            (int reserved-threads)
                            queue
                            thread-group
                            thread-factory)
     (.setDaemon daemon?))))


(defn set-unbounded-core-agent-executors!
  "Overrides the clojure.core agent executors to use the `base-executor`."
  []
  (let [executor (make-unbounded-executor)]
    (set-agent-send-executor! executor)
    (set-agent-send-off-executor! executor)))


(comment
  (defn- task []
    (println "going to sleep" (Thread/currentThread))
    (Thread/sleep 3000)
    (println "awake" (Thread/currentThread)))

  (let [tp (make-queued-thread-pool {:min-threads 1 :max-threads 2})]
    (.start tp)
    (.execute tp task)
    (.execute tp task)
    (.execute tp task)))
