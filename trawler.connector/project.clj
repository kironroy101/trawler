(defproject org.atown.trawler.connector "0.0.1"
  :slug "trawler-connector"
  :description "The Trawler log stream processor."
  :url "https://www.trawler.io"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :maintainer {:email "open@trawler.io"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [cprop "0.1.10"]
                 [mount "0.1.11"]
                 [org.clojure/core.async "0.3.443"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/tools.logging "0.3.1"]
                 [cheshire "5.7.1"]
                 [ch.qos.logback/logback-classic "1.1.3"]
                 [clj-time "0.13.0"]
                 [cc.qbits/spandex "0.4.2"]
                 [com.taoensso/carmine "2.16.0"]
                 [org.yaml/snakeyaml "1.18"]]

  :main org.atown.trawler.connector.core

  :min-lein-version "2.0.0"

  :jvm-opts ["-server" "-Dconf=.lein-env"]
  :resource-paths ["resources"]
  :target-path "target/%s/"

  :plugins [[lein-cprop "1.0.3"]]
  :profiles
  {:uberjar {:omit-source true
             :aot :all
             :uberjar-name "trawler-connector.jar"
             :source-paths ["env/prod/clj"]
             :resource-paths ["env/prod/resources"]}

   :dev           [:project/dev :profiles/dev]
   :test          [:project/dev :project/test :profiles/test]

   :project/dev  {:dependencies [[prone "1.1.4"]
                                 [ring/ring-mock "0.3.0"]
                                 [ring/ring-devel "1.6.1"]
                                 [pjstadig/humane-test-output "0.8.1"]]
                  :plugins      [[com.jakemccrary/lein-test-refresh "0.19.0"]]

                  :source-paths ["env/dev/clj"]
                  :resource-paths ["env/dev/resources"]
                  :repl-options {:init-ns user}
                  :injections [(require 'pjstadig.humane-test-output)
                               (pjstadig.humane-test-output/activate!)]}
   :project/test {:resource-paths ["env/test/resources"]}
   :profiles/dev {}
   :profiles/test {}})
