(ns platform.system.header.range
  (:refer-clojure :exclude [range])
  (:require [clojure.string :as s])
  (:gen-class
   :name platform.system.header.range.Range))

(defrecord Range
    [unit
     range-start
     range-end])

(defn ^Range range [{:keys [unit range-start range-end]
                     :or   {unit        "bytes"
                            range-start 0
                            range-end   "*"}}]
  (let [range-start (or (parse-long range-start) 0)
        range-end   (if (or (= range-end "*")
                            (nil? range-end))
                      :all
                      (parse-long range-end))]
    (->Range unit range-start range-end)))

(defn range-segment [^String segment-str]
  (let [sides (clojure.string/split segment-str #"-")]
    (range {:unit        "bytes"
            :range-start (first sides)
            :range-end   (first (next sides))})))


(def default-range
  {:unit        "bytes"
   :range-start "0"
   :range-end   :all})

(defn wrap-range-header [handler]
  (fn [request]
    (-> (let [range-header (get-in request [:headers "range"])]
          (if (not (empty? range-header))
            (let [range-meta   (s/split range-header #"=")
                  range-limits (s/split (first (rest range-meta)) #"-")
                  range-data   (range {:unit        (first range-meta)
                                       :range-start (first range-limits)
                                       :range-end   (first (rest range-limits))})]
              (assoc request :range-header range-data))
            (assoc request :range-header (range default-range))))
        handler)))

