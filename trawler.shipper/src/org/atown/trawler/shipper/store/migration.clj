(ns org.atown.trawler.shipper.store.migration
  (:require
    [clojure.java.jdbc :as sql]
    [org.atown.trawler.shipper.store.utils :as db-util :refer [db-conn]]))

(defn create-migrations-table []
  ;; check if the table already exists.
  ;; if not, create it.
  (when-not (db-util/table-exists :migrations)
    (sql/db-do-commands db-conn
                        (sql/create-table-ddl
                         :migrations
                         [[:migration "INTEGER"]
                          [:direction "VARCHAR(10)"]
                          [:time "TIMESTAMP DEFAULT CURRENT_TIMESTAMP"]]))))

(defn delete-migrations-table []
  (db-util/delete-table :migrations))

(def migrations
  [
   ;; Migration (0)
   {:name "Create Monitored Files Table"
    :up (fn []
          (sql/db-do-commands
           db-conn
           (sql/create-table-ddl
            :monitored_files
            [[:id "bigint primary key auto_increment"]
             [:inode "INTEGER"]
             [:pointer "INTEGER"]
             [:path "VARCHAR"]
             [:time "TIMESTAMP DEFAULT CURRENT_TIMESTAMP"]])))
    :down (fn []
            (db-util/delete-table :monitored_files))}])

(defn current-migration []
  (let [{m :migration d :direction}
        (or
         (first (sql/query db-conn
                           ["select * from migrations order by time DESC limit 1"]) )
         {:migration -1 :direction "up"})
        ]
    (if (= d "up") m (dec m))))

(defn migrate []
  (let [up-migrations (subvec migrations (inc (current-migration)))]
    (doseq [m up-migrations]
      ((:up m))
      (sql/insert! db-conn :migrations
                   {:migration (.indexOf migrations m)
                    :direction "up"})) ))

(defn rollback []
  (when (< -1 (current-migration))
    (let [m (nth migrations (current-migration))]
      ((:down m))
      (sql/insert! db-conn
                   :migrations
                   {:migration (.indexOf migrations m)
                    :direction "down"}))))

(comment

  (db-util/table-exists :migrations)

  (create-migrations-table)
  (delete-migrations-table)

  (current-migration)
  (migrate)
  (db-util/table-exists :monitored_files)
  (rollback)
  (db-util/table-exists :monitored_files)

)
