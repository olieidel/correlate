(defproject correlate "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/data.csv "0.1.4"]
                 [org.clojure/data.xml "0.0.8"]
                 [clj-time "0.14.4"]
                 [incanter "1.9.3"]]
  :main ^:skip-aot correlate.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
