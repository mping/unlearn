(ns unlearn.server
  (:require [ring.adapter.jetty9 :refer [run-jetty]]
            [unlearn.virtual.executor :as executor]
            [aleph.http :as http]
            [clojure.java.jdbc :as j]
            [manifold.deferred :as d]))

(def mysql-db {:dbtype "mysql"
               :dbname "test"
               :user "test"
               :password "test"})


(def counter (atom 0))

(defn- handler [request]
  (try
    (swap! counter inc)
    (println "accept: " @counter)
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (j/query mysql-db ["select sleep(?)" 10 #_(rand-int 10)])}
    (finally
      (swap! counter dec))))

(def exec (manifold.executor/utilization-executor 1))

(defn- mhandler [req]
  (println "acceptf")
  (-> (d/chain (d/future
                 (swap! counter inc)
                 (println (str "aleph accept: " @counter))
                 (j/query mysql-db ["select sleep(?)" (rand-int 10)]))
               (fn [r]
                 {:status  200
                  :headers {"Content-Type" "text/html"}
                  :body    r}))
      (d/finally
        (fn []  (swap! counter dec)))))

(defn start-loom []
  (run-jetty #'handler {:port 8080
                        :join? false
                        :thread-pool (executor/thread-pool {:stop-timeout 10})}))

(defn start-plain []
  (run-jetty #'handler {:port 8081
                        :join? false}))

(defn start-aleph []
  (http/start-server #'mhandler  {:port 8082}))


(comment
  (executor/set-core-agent-executors-virtual!)
  (executor/set-default-uncaught-exception-handler!)
  ;; wrk -t12 -c400 -d30s http://127.0.0.1:808{0,1,2}
  (def loom (start-loom))
  (def plain (start-plain))
  (def aleph (start-aleph))
  (.stop loom)
  (.stop plain)
  (.close aleph)
  "end")

