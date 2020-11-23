(ns unlearn.demo.servers
  (:require [ring.adapter.jetty9 :refer [run-jetty]]
            [unlearn.virtual.jetty :as jetty]
            [aleph.http :as http]
            [clojure.java.jdbc :as j]
            [manifold.deferred :as d]))

(def mysql-db {:dbtype   "mysql"
               :dbname   "test"
               :user     "test"
               :password "test"})

;; while :; do docker-compose exec mysql mysql -uroot -proot -Dtest -e 'SHOW STATUS WHERE variable_name LIKE "Threads_%" OR variable_name = "Connections"'; sleep 1; done
;; docker-compose exec  mysql mysql -uroot -proot -Dtest -e "set global max_connections=1000"
(def counter (atom 0))

(defn periodically
  [f interval]
  (doto (Thread.
          #(try
             (while (not (.isInterrupted (Thread/currentThread)))
               (Thread/sleep interval)
               (f))
             (catch InterruptedException _)))
    (.start)))

(comment
  (def printer (periodically (fn [] (println (str "inflight: " @counter))) 1000))
  (reset! counter 0)
  (.interrupt printer))


(defn- handler [request]
  (swap! counter inc)
  (try
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (j/query mysql-db ["select sleep(?)" (rand-int 10)])}
    (finally
      (swap! counter dec))))

(defn- handler-deferred [req]
  (swap! counter inc)
  (-> (d/chain (d/future
                 (j/query mysql-db ["select sleep(?)" (rand-int 10)]))
               (fn [r]
                 {:status  200
                  :headers {"Content-Type" "text/html"}
                  :body    r}))
      (d/finally
        (fn [] (swap! counter dec)))))

(defn start-loom [p]
  (run-jetty #'handler {:port        p
                        :join?       false
                        :thread-pool (jetty/thread-pool {:stop-timeout 10})}))

(defn start-plain [p]
  (run-jetty #'handler {:port  p
                        :join? false}))

(defn start-aleph [p]
  (http/start-server #'handler-deferred {:port p}))

(comment
  ;; wrk -t12 -c400 -d30s http://127.0.0.1:808{0,1,2}
  (def loom (start-loom 8080))
  (def plain (start-plain 8081))
  (def aleph (start-aleph 8082))
  (.stop loom)
  (.stop plain)
  (.close aleph)
  "end")

