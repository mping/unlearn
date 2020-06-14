(ns unlearn.client
  (:require [clojure.string :as s]
            [java-http-clj.core :as http]
            [unlearn.threadpool :as tp])
  (:import (java.util.concurrent Executors)
           (java.util.concurrent CompletableFuture)))

(def vclient (http/build-client {:follow-redirects :always
                                 :executor         (tp/make-unbounded-executor)}))

(def client (http/build-client {:follow-redirects :always
                                :executor         (Executors/newWorkStealingPool)}))

(defn server-header [url cli]
  ;; this is what a sync api call looks like
  (try
    (-> (http/send {:uri url :method :get :timeout 5000} {:client cli})
        :headers
        (get "server" "unknown"))
    (catch Exception _ "error")))

(defn server-header-async [url cli]
  ;; this is what an async api call looks like

  ;; Takes an optional callback and exception handler
  (http/send-async {:uri url :method :get :timeout 5000}
                   {:client cli}
                   (fn [r] (get (:headers r) "server" "unknown"))
                   (fn [_] "error")))

;; alexa top 1m
;; http://s3.amazonaws.com/alexa-static/top-1m.csv.zip

(defn normalize [s]
  (if (not (s/starts-with? s "http"))
    (str "http://" s)
    s))

(def sites
  (->> (s/split-lines (slurp "resources/top-1m.csv"))
       (map #(last (s/split % #",")))
       (map normalize)))

(defn sample [n]
  (->> (repeatedly #(rand-int (count sites)))
       (take (min n (count sites)))
       (map #(nth sites %))))


(defn allof [cfs]
  @(CompletableFuture/allOf (into-array cfs))
  (->> cfs
       (filter #(.isDone %))
       (map #(if (.isDone %) (.get %) "error"))))

(comment
  (let [rand-sites    (take 100 sites)
        ;; async calls are a pain to handle
        async-client  (fn [] (allof (into-array (map #(server-header-async % client) rand-sites))))
        async-vclient (fn [] (allof (into-array (map #(server-header-async % vclient) rand-sites))))
        ;; everybody knows sync, right?
        sync-vclient  (fn [] (map #(server-header % vclient) rand-sites))]

    (println "@>" rand-sites)
    ;; the first call probably prime the DNS cache
    (time (println "priming..." (async-client)))
    (time (println "Async Client" (async-client)))
    (time (println "Async VClient" (async-vclient)))
    (time (println "Sync VClient" (sync-vclient)))))
