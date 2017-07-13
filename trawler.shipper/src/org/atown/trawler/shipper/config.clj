(ns org.atown.trawler.shipper.config
  (:require [cprop.core :refer [load-config]]
            [cprop.source :as source]
            [mount.core :refer [args defstate]]))

(def ^:dynamic *runtime-args* {})
(defn start-env
  []
   (load-config
    :merge
    [(args)
     (source/from-system-props)
     (source/from-env)
     *runtime-args*]))

(defstate env :start (start-env))
