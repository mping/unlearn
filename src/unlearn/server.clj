(ns unlearn.server
  (:require [ring.adapter.jetty9 :refer [run-jetty]]
            [unlearn.virtual.executor :as executor]))

(defn- handler [request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "Hello World"})

(defn start-loom []
  (run-jetty #'handler {:port 8080
                        :join? false
                        :max-threads 1
                        :thread-pool (executor/thread-pool)}))

(defn start-loom-queued []
  (run-jetty #'handler {:port 8081
                        :join? false
                        :max-threads 1
                        :thread-pool (executor/queued-thread-pool)}))

(defn start-plain []
  (run-jetty #'handler {:port 8082
                        :join? false
                        :max-threads 10}))

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

