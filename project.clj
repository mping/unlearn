(defproject unlearn "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.2.603"]
                 [org.clojure/tools.logging "1.1.0"]
                 [java-http-clj "0.4.1"]
                 [ring/ring-core "1.6.3"]
                 [info.sunng/ring-jetty9-adapter "0.12.8"]
                 ;; for comparison
                 [manifold "0.1.9-alpha3"]]
  :jvm-opts ["-XX:-UseContinuationChunks"]
  :repl-options {:init-ns unlearn.server})
