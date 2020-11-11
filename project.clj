(defproject unlearn "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.2.603"]
                 [org.clojure/tools.logging "1.1.0"]
                 [java-http-clj "0.4.1"]
                 [ring/ring-core "1.6.3"]
                 [info.sunng/ring-jetty9-adapter "0.12.8"]

                 ;;
                 [org.clojure/java.jdbc "0.7.11"]
                 [mysql/mysql-connector-java "8.0.22"]
                 [aleph "0.4.7-alpha5"]

                 ;; for comparison
                 [criterium "0.4.6"]
                 [manifold "0.1.9-alpha3"]]
  :jvm-opts ["-XX:-UseContinuationChunks"]
  :repl-options {:init-ns unlearn.server})
