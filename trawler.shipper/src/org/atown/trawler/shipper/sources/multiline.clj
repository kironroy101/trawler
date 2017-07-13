(ns org.atown.trawler.shipper.sources.multiline
  (:require [clojure.core.async :as async]
            [clojure.string :as string]
            [clojure.tools.logging :as log]))

(defn event-reducer [pattern]
  (let [p (if (string? pattern) (re-pattern pattern) pattern)
        entry-start? (fn [s] (and (some? s)
                                  (some? (re-matches p s))))
        ;; !!! STATE !!!
        entry-buffer (atom [])
        ;; Utility functions for manipulating and querying the
        ;; entry buffer
        entry-buffer-str (fn [] (string/join "\n" @entry-buffer))
        reset-buffer (fn [v] (reset! entry-buffer [v]))
        clear-buffer (fn [] (reset! entry-buffer []))
        conj-buffer (fn [v] (swap! entry-buffer conj v))]
    [(fn [line]
      (let [is-start (entry-start? line)]
        ;; If the value matches the pattern
        (if is-start
          ;; emit the current
          (let [b-str (entry-buffer-str)]
            (reset-buffer line)
            b-str)
          ;; ELSE
          (do (conj-buffer line)
              nil))))
     (fn []
       (let [b-str (entry-buffer-str)]
         (clear-buffer)
         b-str))]))

(defn event-reducer-with-timeout [pattern timeout]
  (let [[push-line flush-buffer] (event-reducer pattern)
        in (async/chan 5000)
        out (async/chan 1 (remove (fn [v] (or (nil? v) (string/blank? v)))))]

    (async/go-loop []
      (let [v (async/<! in)]
        (when (some? v)
          (let [event (push-line v)]
            (when (some? event)
              (async/>! out event)))
          (recur))))

    [(fn [l] (async/>!! in l))
     (fn []
       (let [[v ch] (async/alts!! [out (async/timeout timeout)])]
         ;; if value pulled from the out channel, then hand it over
         ;; otherwise, flush-buffer
         (cond (= ch out) v
               :default (flush-buffer)))
       )
     (fn []
       (async/close! in)
       (async/close! out))]))

(comment

  (let [[push pull close]
        (event-reducer-with-timeout
         "^=INFO REPORT==== (.*)" 3000)]
    (dotimes [_ 3]
      (push "=INFO REPORT==== 13-Jun-2017::13:38:10 ===")
      (push "started TCP Listener on [::]:5672")
      (push ""))
    (Thread/sleep 4000)
    (dotimes [_ 3]
      (clojure.pprint/pprint (pull)))
    (close)
    )

  )
