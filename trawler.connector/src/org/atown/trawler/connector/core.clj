(ns org.atown.trawler.connector.core
  (:require [org.atown.trawler.connector.config :as config :refer [env]]
            [mount.core :as mount :refer [defstate]]
            [clojure.tools.cli :refer [parse-opts]]
            [cheshire.core :as json]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [taoensso.carmine :as car :refer (wcar)]
            [taoensso.carmine.message-queue :as car-mq]
            [qbits.spandex :as s]
            [qbits.spandex.utils :as s-utils]
            [clj-time.core :as time]
            [clj-time.coerce :as tcoerce]
            [clojure.java.io :as io])
  (:import [org.yaml.snakeyaml Yaml]
           [java.util LinkedHashMap ArrayList])


  (:gen-class))

(defn get-marg
  [default & path]
  (get-in (mount/args) path default))

(defn read-yaml [path]
  (let [y (Yaml.)
        jyaml (.load y (io/input-stream path))]
    (-> (walk/prewalk
         (fn [v]
           (cond
             (instance? LinkedHashMap v)
             (into {} v)
             (instance? ArrayList v)
             (into [] v)
             :default v))
         jyaml)
        walk/keywordize-keys)))

(defn redis-connection []
  {:pool {}
   :spec {:host (or (get-in (mount/args) [:redis :host]) "127.0.0.1")
          :port (or (get-in (mount/args) [:redis :port]) 6379)}})

(defmacro wcar* [& body] `(car/wcar (redis-connection) ~@body))

(defstate es-client
  :start (s/client {:hosts
                    (into []
                          (map
                           (fn [{:keys [host port protocol]}]
                             (format "%s://%s:%s" protocol host port))
                           (get-marg [{:host "127.0.0.1" :port 9200 :protocol "http"}]
                                     :elasticsearch :hosts)))
                    :default-headers
                    {"Content-Type" "application/json"}})
  :stop (s/close! es-client))

(defn index-log-event [event]
  (let [e (-> (select-keys event [:msg :service :host :time])
              (update :time (fn [t]
                              (if (number? t)
                                (str (tcoerce/from-long t))
                                t))))
        id (hash e)
        date (str (time/today))]
    (s/request es-client
               {:url (s-utils/url [(keyword (str (get-marg "trawler"
                                                           :elasticsearch :indexPrefix)
                                                 "-"
                                                 date))
                                   :log-event (str id)])
                :method :put
                :body e})))

(defn start-redis-workers []
  (let [queues (get-marg ["trawler-shipper-queue-1"]
                         :redis :queues)]
    (doall
     (map
      (fn [q]
        (car-mq/worker
         (redis-connection) "trawler-shipper-queue-1"
         {
          ;; Tweak the behavior for fairly constant workload
          ;; and lower latency
          ;; see: https://github.com/ptaoussanis/carmine/issues/159
          :eoq-backoff-ms (constantly 10) ; Back off 10ms when no work to do
          :throttle-ms 10 ; Wait 10ms between polling ops

          :handler
          (fn [{:keys [message attempt]}]
            (try
              (do
                (index-log-event message)
                {:status :success})
              (catch Exception e
                (if (< attempt 100)
                  (do
                    (log/errorf "(Attempt %s) (Will Retry) Error indexing log event to ES: %s"
                                attempt
                                (str (select-keys message [:host :service :time])))
                    {:status :retry
                     :backoff-ms (* 10 1000)} )
                  (do
                    (log/errorf e
                                "(Attempt %s) (Won't Retry) Error indexing log event to ES: %s"
                                attempt
                                (str (select-keys message [:host :service :time])))
                    {:status :error
                     :throwable e} ))))
            )
          }))
      queues))))

(defn stop-redis-workers [workers]
  (doseq [w workers]
    (car-mq/stop w)))

(mount/defstate redis-workers
  :start (start-redis-workers)
  :stop (stop-redis-workers redis-workers))

(def cli-options
  [["-c" "--config CONFIG_JSON_FILE" "The config file for the shipper"]])

(defn stop-app []
  (doseq [component (:stopped (mount/stop))]
    (log/info component "stopped")))

(defn start-app [args]
  (let [config-path (or (-> args
                            (parse-opts cli-options)
                            :options :config)
                        "config.yml")
        config (read-yaml config-path)
        started (:started
                  (mount/start-with-args config))]
    (log/info "Started trawler.connector" started)
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop-app))))

(defn -main [& args]
  (start-app args)
  (loop []
    (Thread/sleep (* 1000 60 60 24))))

(comment
  (start-app [])

  (index-log-event
   {:service "example"
    :msg "Example Log Message from Example Service"
    :time (System/currentTimeMillis)
    :host "demolition"})

  (stop-app)
  )
