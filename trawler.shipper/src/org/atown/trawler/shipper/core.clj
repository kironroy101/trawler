(ns org.atown.trawler.shipper.core
  (:require [org.atown.trawler.shipper.config :as config :refer [env]]
            [org.atown.trawler.shipper.sources.file :as file-source]
            [org.atown.trawler.shipper.sources.multiline :as multiline]
            [org.atown.trawler.shipper.store.utils :refer [db-conn]]
            [org.atown.trawler.shipper.store.migration :as migration]
            [org.atown.trawler.shipper.outputs.core :as output]
            [mount.core :as mount :refer [defstate]]
            [clojure.tools.cli :refer [parse-opts]]
            [cheshire.core :as json]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.nrepl.server :as repl]

            )
  (:import [org.yaml.snakeyaml Yaml]
           [java.io FileNotFoundException]
           [java.nio.file AccessDeniedException])
  (:gen-class))

;; (defstate ^{:on-reload :noop}
;;   repl-server
;;   :start (repl/start-server :port (get env :nrepl-port 7000))
;;   :stop (repl/stop-server repl-server))

(defn get-hostname [] (.. java.net.InetAddress getLocalHost getHostName))

(defn read-yaml [path]
  (let [y (Yaml.)
        jyaml (.load y (io/input-stream path))]
    (-> (walk/prewalk
         (fn [v]
           (cond
             (instance? java.util.LinkedHashMap v)
             (into {} v)
             (instance? java.util.ArrayList v)
             (into [] v)
             :default v))
         jyaml)
        walk/keywordize-keys)))

(defn start-shippers []
  (let [conf (mount/args)
        r-conf (:redis conf)
        services (:services conf)
        shippers
        (doall
         (map
          (fn [service]
            (let  [[push-mline pull-mline close-mline]
                   (multiline/event-reducer-with-timeout
                    (:multilinePattern service)
                    3000)
                   [write-to-redis close-redis] (output/redis-log-event-output
                                                 (get r-conf :queue "trawler-shipper-queue-1")
                                                 (get r-conf :pollMilliseconds 5000))
                   file-monitor
                   (file-source/start-monitoring-file
                    (:filepath service)
                    (fn [lines]
                      (async/go (doseq [l lines]
                                  (push-mline l)))
                      (loop []
                        (let [event (pull-mline)]
                          (when-not (or (string/blank? event)
                                        (nil? event))
                            (write-to-redis
                             {:msg event
                              :service (:service service)
                              :host (get-hostname)
                              :time (System/currentTimeMillis)})
                            (recur))))))]
              [close-redis #(do (file-source/stop-monitoring-file file-monitor)
                                (close-mline))]))
          services))]
    shippers))

(defn stop-shippers [shippers]
  (doseq [s shippers]
    ((first s))
    ((second s))))


(mount/defstate file-shippers
  :start (start-shippers)
  :stop (stop-shippers file-shippers))

(def cli-options
  [["-c" "--config CONFIG_JSON_FILE" "The config file for the shipper"]])

(defn stop-app []
  (doseq [component (:stopped (mount/stop))]
    (log/info component "stopped")))

(defn files-exist [conf]
  (->> (:services conf)
       (map :filepath)
       (map (fn [f] [f (fs/exists? f)]))
       (into [])))

(defn start-app [args]
  (let [config-path (or (-> args
                            (parse-opts cli-options)
                            :options :config)
                        "config.yml")
        config (read-yaml config-path)
        missing (->> (files-exist config)
                     (filter (fn [[fpath exists]] (not exists)))
                     (map first))]

    (if (empty? missing)
      (do
        (mount/start (mount/with-args [#'db-conn] config))
        (migration/create-migrations-table)
        (migration/migrate)
        (try
          (log/info "Started Trawler Shipper"
                    (:started
                      (mount/start-with-args config)))
          (.addShutdownHook (Runtime/getRuntime) (Thread. stop-app))
          true
          (catch Exception e
            (let [bail-msg (format "Error Starting. Cause: %s" (.getCause e))]
              (cond
                (instance? FileNotFoundException (.getCause e))
                (log/error bail-msg)
                (instance? AccessDeniedException (.getCause e))
                (log/error bail-msg
                           (str
                            "-- Permissions to access all sibiling directories and files of"
                            " monitored logs are required."))
                :default
                (log/error e bail-msg))
              false))))
      (do
        (log/errorf "Couldn't find files: %s Please update your configuration." (str (into [] missing)))
        false))))

(defn -main [& args]
  (if (start-app args)
    (loop []
      (Thread/sleep (* 1000 60 60 24)))))


(comment
  (-main)
  (start-app ["--config" "hiron-config.yml"])
  (stop-app)

  (clojure.test/is (= (read-yaml "config.yaml")
                      (walk/keywordize-keys
                       (json/parse-string (slurp "config.json")))) )


  (println (first file-shippers))

  )
