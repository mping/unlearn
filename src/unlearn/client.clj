(ns unlearn.client
  (:require [clojure.string :as s]
            [java-http-clj.core :as http]
            [unlearn.virtual.executor :as executor])
  (:import (java.util.concurrent Executors)
           (java.util.concurrent CompletableFuture ExecutorService)))

(def virtual-executor (executor/executor))
(def workstealing-executor (Executors/newWorkStealingPool))

(def vclient (http/build-client {:follow-redirects :always
                                 :executor         virtual-executor}))

(def client (http/build-client {:follow-redirects :always
                                :executor workstealing-executor}))

(def timeout 50000)

(defn server-header [url cli]
  ;; this is what a sync api call looks like
  (try
    (-> (http/send {:uri url :method :get :timeout timeout} {:client cli})
        :headers
        (get "server" "unknown"))
    (catch Exception _ "error")))

(defn server-header-async [url cli]
  ;; this is what an async api call looks like

  ;; Takes an optional callback and exception handler
  (http/send-async {:uri url :method :get :timeout timeout}
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

(defn allof [cfs]
  @(CompletableFuture/allOf (into-array cfs))
  (->> cfs
       (map #(if (.isDone %) (.get %) "error"))))

(defn vmap
  "Like pmap, but using virtual threads"
  [f coll]
  (with-open [^ExecutorService e (executor/executor)]
    (let [tasks (into [] (map (fn [el] (^:once fn [] (f el))) coll))
          tasks (.submitTasks e tasks)]
      (->> (CompletableFuture/completed tasks)
           (.iterator)
           (iterator-seq)
           (map #(.join %))))))

(comment
  (java.security.Security/setProperty "networkaddress.cache.ttl" "0")
  (java.security.Security/setProperty "networkaddress.cache.negative.ttl " "0")
  ;; https://wiki.openjdk.java.net/display/loom/Networking+IO

  (future
    (let [rand-sites    (take 100 sites)
          ;; async calls are a pain to handle
          async-client  (fn [] (allof (into-array (map #(server-header-async % client) rand-sites))))
          async-vclient (fn [] (allof (into-array (map #(server-header-async % vclient) rand-sites))))
          ;; everybody knows sync, right?
          sync-vclient  (fn [] (vmap #(server-header % vclient) rand-sites))]
      ;;priming dns
      (println "priming dns cache")
      (async-client)
      (println "@>" rand-sites)
      ;; the first call probably prime the DNS cache
      (time (println "Async Client" (async-client)))
      (time (println "Async VClient" (async-vclient)))
      (time (println "Sync VClient" (sync-vclient)))))

  (time
    (let [avail-procs (+ 2 (.. Runtime getRuntime availableProcessors))
          total       200]
      (println "estimated duration:" (int (/ total avail-procs)))
      (count (pmap #(do (Thread/sleep 1000) %) (range total)))))

  (time
    (let [total 200]
      (count (pmap #(do (Thread/sleep 1000) %) (range total)))))
  "end")




