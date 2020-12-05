(ns unlearn.demo.servers
  (:require [ring.adapter.jetty9 :refer [run-jetty]]
            [unlearn.virtual.jetty :as jetty]
            [aleph.http :as http]
            [clojure.java.jdbc :as jdbc]
            [manifold.deferred :as d]
            [reitit.ring :as ring]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m]
            [jsonista.core :as j]))

(def mysql-db {:dbtype   "mysql"
               :dbname   "test"
               :user     "test"
               :password "test"})

;; while :; do docker-compose exec mysql mysql -uroot -proot -Dtest -e 'SHOW STATUS WHERE variable_name LIKE "Threads_%" OR variable_name = "Connections"'; sleep 1; done
;; docker-compose exec  mysql mysql -uroot -proot -Dtest -e "set global max_connections=1000"

;;;;
;; APP

(defn run-query [db]
  (-> (jdbc/query db ["select sleep(FLOOR(RAND()*10)) as s"])
      (j/write-value-as-bytes)))

(defn ok [_] {:status 200 :body ""})

(defn query [_]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (run-query mysql-db)})

(defn count-request-manifold [_]
  (d/chain (d/future
             (run-query mysql-db))
           (fn [r]
             {:status  200
              :headers {"Content-Type" "text/html"}
              :body    r})))

(def exception-middleware
  (exception/create-exception-middleware
    (merge
      exception/default-handlers
      {::exception/wrap (fn [handler e request]
                          (println "ERROR" (pr-str (:uri request)) e)
                          (handler e request))})))

(def app
  (ring/ring-handler
    (ring/router
      [["/baseline" {:get {:handler ok}}]
       ["/" {:get {:handler query}}]]
      {:data {:muuntaja   m/instance
              :middleware [exception-middleware
                           muuntaja/format-middleware]}})))


(def app-manifold
  (ring/ring-handler
    (ring/router
      [["/baseline" {:get {:handler ok}}]
       ["/" {:get {:handler count-request-manifold}}]])))


(defn start-loom [p]
  (run-jetty #'app {:port        p
                    :join?       false
                    :thread-pool (jetty/thread-pool {:stop-timeout 10})}))

(defn start-plain [p]
  (run-jetty #'app {:port  p
                    :join? false}))

(defn start-aleph [p]
  ;; aleph knows how to handle deferreds
  (http/start-server #'app-manifold {:port p}))

(comment
  ;; wrk -t12 -c400 -d30s http://127.0.0.1:808{0,1,2}
  (def loom (start-loom 8080))
  (def plain (start-plain 8081))
  (def aleph (start-aleph 8082))
  (.stop loom)
  (.stop plain)
  (.close aleph)
  "end")

