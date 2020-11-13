(defproject unlearn "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.logging "1.1.0"]]


  :profiles {:dev {:dependencies [;; http
                                  [aleph "0.4.7-alpha5"]
                                  [manifold "0.1.9-alpha3"]
                                  [java-http-clj "0.4.1"]
                                  [ring/ring-core "1.6.3"]
                                  [info.sunng/ring-jetty9-adapter "0.12.8"]

                                  ;; reitit+malli
                                  [metosin/jsonista "0.2.6"]
                                  [metosin/reitit "0.5.10"]

                                  ;; io
                                  [org.clojure/java.jdbc "0.7.11"]
                                  [mysql/mysql-connector-java "8.0.22"]]

                   :source-paths ["demo"]}}
  :jvm-opts ["-XX:-UseContinuationChunks"]
  :repl-options {:init-ns unlearn.demo.server})
