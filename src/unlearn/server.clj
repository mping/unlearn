(ns unlearn.server
  (:require [ring.adapter.jetty9 :refer [run-jetty]]
            [unlearn.virtual.executor :as executor])
  (:import (org.eclipse.jetty.util.thread ThreadPool)))

(defn- handler [request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "Hello World"})

(defn start-loom []
  (run-jetty #'handler {:port 8080
                        :join? false
                        :thread-pool (executor/thread-pool {:stop-timeout 10})}))

(defn start-loom-queued []
  (run-jetty #'handler {:port 8081
                        :join? false
                        :thread-pool (executor/queued-thread-pool {:max-threads 1})}))

(defn start-plain []
  (run-jetty #'handler {:port 8082
                        :join? false
                        :max-threads 8}))

(instance? ThreadPool (executor/thread-pool {:stop-timeout 10}))

(comment
  (executor/set-core-agent-executors-virtual!)
  (executor/set-default-uncaught-exception-handler!)
  ;; wrk -t12 -c400 -d30s http://127.0.0.1:808{0,1,2}
  (def loom (start-loom))
  (def loomq (start-loom-queued))
  (def plain (start-plain))
  (.stop loom)
  (.stop loomq)
  (.stop plain)
  "end")

