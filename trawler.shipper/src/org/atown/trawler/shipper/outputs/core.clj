(ns org.atown.trawler.shipper.outputs.core
  (:require
    [clojure.core.async :as async]
    [clojure.tools.logging :as log]
    [clojure.string :as string]
    [org.atown.trawler.shipper.sources.file :as file-source]
    [org.atown.trawler.shipper.sources.multiline :as multiline]
    [org.atown.trawler.shipper.config :refer [ env ]]
    [mount.core :as mount]
    [taoensso.carmine :as car :refer (wcar)]
    [taoensso.carmine.message-queue :as car-mq]
    )
  (:import [java.net ConnectException]
           [clojure.lang ExceptionInfo]))

(defn redis-connection []
  {:pool {}
   :spec {:host (or (get-in (mount/args) [:redis :host]) "127.0.0.1")
          :port (or (get-in (mount/args) [:redis :port]) 6379)}})

(defmacro wcar* [& body] `(car/wcar (redis-connection) ~@body))

(defn redis-log-event-output [queue-name ease-off-ms]
  (let [in (async/chan 1)
        closed (atom false)]
    (async/go-loop []
      (let [v (async/<! in)]
        (when (some? v)
          (loop []
            (let [success (atom true)]
              (try
                (wcar* (car-mq/enqueue queue-name v))
                (catch ExceptionInfo e
                  (do (log/error "Connection Refused (Redis)" e)
                      (reset! success false)))
                (catch Throwable e
                  (log/error "Failure to write log event to Redis" e)))
              (when-not @success
                (async/<! (async/timeout ease-off-ms))
                (when-not @closed (recur)))))
          (recur))))
    [(fn [event]
       (async/>!! in event))
     (fn []
       (async/close! in)
       (reset! closed true))]))

(comment

 (def my-worker
   (car-mq/worker redis-connection "trawler-shipper-queue-1"
                  {:handler (fn [{:keys [message attempt]}]
                              (println "Received" message)
                              {:status :success})}))

 (car-mq/stop my-worker)


 (def redis-writer-and-close (redis-log-event-output "example-queue-1" (* 10 1000)))
 (def redis-writer (first redis-writer-and-close))
 (def close (second redis-writer-and-close))

 (dotimes [n 20] (redis-writer {:hello (str "world " n)}))

 (close)

)
