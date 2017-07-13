(ns org.atown.trawler.shipper.sources.file
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [org.atown.trawler.shipper.store.utils :as db-utils :refer [db-conn]]
            [clojure.java.jdbc :as sql]
            [clojure.set :as set]
            [hawk.core :as file-watch]
            [me.raynes.fs :as fs])
  (:import
    [java.net URI]
    [java.nio.file Path Paths Files LinkOption]
    [java.nio.file.attribute BasicFileAttributes]
    [java.io FileInputStream RandomAccessFile]
    ))

(def n-cpu (.availableProcessors (Runtime/getRuntime)))


(defn get-inode-val [file-path]
  (let [path (Paths/get (.toURI (io/file file-path)))
        attr (Files/readAttributes path  BasicFileAttributes
                                   (into-array LinkOption []))
        fkey (str (.fileKey attr))
        ino (.substring fkey
                        (+ 4 (.indexOf fkey "ino=") )
                        (.indexOf fkey ")"))]
    (if (some? ino)
      (Integer/parseInt ino)
      nil)))

(defn- get-current-inode-entry [file-path]
  (let [inode (get-inode-val file-path)
        matching-entries (into #{}
                               (sql/find-by-keys
                                db-conn :monitored_files
                                {:path file-path}) )
        current-inode-entry (into #{}
                                  (filter #(= (:inode %) inode)
                                          matching-entries))]
    (when (empty? current-inode-entry)
      (sql/insert! db-conn :monitored_files
                   {:inode inode
                    :pointer 0
                    :path file-path}))

    (or (first current-inode-entry)
        (first (sql/find-by-keys db-conn :monitored_files
                                 {:inode inode})))))

(defn- update-inode-entry [file-path raf]
  (sql/update! db-conn :monitored_files
               {:pointer (.getFilePointer raf)}
               ["inode = ?" (get-inode-val file-path)]))

(defn- cleanup-old-entries [file-path]
  (let [inode (get-inode-val file-path)
        matching-entries (into #{}
                               (sql/find-by-keys
                                db-conn :monitored_files
                                {:path file-path}))
        current-inode-entry (into #{}
                                  (filter #(= (:inode %) inode) matching-entries))
        old-entries (set/difference matching-entries current-inode-entry)]
    ;; delete entries that match the path, but not the inode
    ;; This means that the file at the path location has changed
    (doseq [e old-entries]
      (sql/delete! db-conn :monitored_files
                   ["id = ?" (:id e)]))))


(defn- drain-raf-no-db [raf line-seq-fn]
  (let [block (atom [])]
    (loop []
      (let  [line (.readLine raf)]
        (when (some? line)
          (do (swap! block conj line) (recur)))))
    (line-seq-fn @block))
  )

(defn- drain-raf [file-path raf line-seq-fn]
  (try
    (do
      (drain-raf-no-db raf line-seq-fn)
        (update-inode-entry file-path raf))
    (catch Exception e
      (log/error e "Error draining Random Access File"
                 file-path raf line-seq-fn))))

(defmulti handle-file-event
  (fn [ctx e]
    (:kind e)))

(defmethod handle-file-event :create
  [{:keys [file-path] :as ctx} _]
  ;; Start a new monitor for the created file
  (Thread/sleep 1000)
  ;; call get-current-inode-entry to initialize a db-entry
  (get-current-inode-entry file-path)
  (cleanup-old-entries file-path)
  (assoc ctx :raf (RandomAccessFile. file-path "r")))

(defmethod handle-file-event :delete
  [{:keys [raf line-seq-fn] :as ctx} _]
  (try (do (drain-raf-no-db raf line-seq-fn)
           (.close raf) )
       (catch Throwable e (log/error e "Error Handling Delete")))
  ctx)

(defmethod handle-file-event :modify
  [{:keys [file-path raf line-seq-fn] :as ctx} e]
  (drain-raf file-path raf line-seq-fn)
  ctx)

(defn make-file-event-handler []
  (let [buffer (async/chan)
        out (async/chan)]
    (async/go-loop []
      (let [v (async/<! buffer)]
        (when (some? v)
          (async/>! out (apply handle-file-event v))
          (recur))))

    [(fn [ctx e]
       (async/>!! buffer [ctx e])
       (async/<!! out))
     (fn [] (async/close! buffer))]))

(defn- setup-monitoring [file-path line-seq-fn raf]
  (cleanup-old-entries file-path)
  (let [inode-entry (get-current-inode-entry file-path)
        start-pos (:pointer inode-entry)]
    ;; start reading from the pos stored w/ in the inode entry.
    (.seek raf start-pos)
    (drain-raf file-path raf line-seq-fn)))

(defn start-monitoring-file [file-path line-seq-fn]
  (if (fs/exists? file-path)
    (do
      (let [raf (RandomAccessFile. file-path "r")
            [handler close-handler] (make-file-event-handler)]
        ;; start reading from the pos stored w/ in the inode entry.
        (async/go (setup-monitoring file-path line-seq-fn raf))
        [(file-watch/watch!
          [{:paths [file-path]
            :context (fn [_] {:file-path file-path
                              :line-seq-fn line-seq-fn
                              :raf raf})
            :handler handler}])
         close-handler]))
    ;; ELSE
    (do (log/errorf "Could not monitor '%s'. It could not be found."
                    file-path)
        nil)
    ))

(defn stop-monitoring-file [[watch close-handler-fn]]
  (when (some? watch)
    (file-watch/stop! watch)
    (close-handler-fn)))

(comment

  (def test-log "/home/hiron/example-logs/example1.log")
  (mount.core/start #'db-conn)
  (mount.core/stop #'db-conn)
  (cleanup-old-entries test-log)
  (def watch-mon (start-monitoring-file
                  test-log (fn [v] (do (println v)
                                       v))))

  (let [{:keys [pointer]} (get-current-inode-entry test-log)
        r (RandomAccessFile. test-log "r")
        ]
    (.seek r pointer)
    (.readLine r))

  (clojure.pprint/pprint (sql/find-by-keys db-conn :monitored_files
                    {:path test-log}) )

  (get-inode-val test-log)
  (cleanup-old-entries test-log)

  (stop-monitoring-file watch-mon)

  )
