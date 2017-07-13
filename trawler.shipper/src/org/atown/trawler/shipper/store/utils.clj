(ns org.atown.trawler.shipper.store.utils
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [mount.core :as mount :refer [defstate]])
  (:import [com.mchange.v2.c3p0 ComboPooledDataSource]))

(defn pool [spec]
  (let [cpds
        (doto (ComboPooledDataSource.)
          (.setDriverClass (:classname spec))
          (.setJdbcUrl (str "jdbc:" (:subprotocol spec) ":" (:subname spec))))]
    {:datasource cpds}))

(defstate db-conn
  :start (pool
          (let [m (mount/args)
                conn {:classname "org.h2.Driver"
                      :subprotocol "h2:file"
                      :subname (get-in m [:settings :localstorePath]
                                       ".trawlershipper")}]
            conn)
          )
  :stop (.close (:datasource db-conn)))

(defn table-exists [table]
  (let [t-name (if (keyword? table)
                 (string/upper-case
                  (name table))
                 table)]
    (let [ps (sql/prepare-statement (sql/get-connection db-conn)
                                    "select count(*) as count from information_schema.tables where table_name = ?"
                                    )
          resp (sql/query db-conn [ps t-name]
                          )
          exists (< 0 (-> resp first :count))]
      exists)))

(defn delete-table [table]
  (sql/db-do-commands
   db-conn
   (sql/drop-table-ddl table)))
