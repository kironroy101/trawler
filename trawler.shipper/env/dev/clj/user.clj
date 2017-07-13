(ns user
  (:require [mount.core :as mount]
            [org.atown.trawler.shipper.core]
            ))

(defn start []
  (mount/start))

(defn stop []
  (mount/stop))

(defn restart []
  (stop)
  (start))
