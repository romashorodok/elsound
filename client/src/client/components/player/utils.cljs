(ns client.components.player.utils
  (:require [clojure.string :as s]))

(defn buffered-timestamp?
  "Iterate through source-buffer and check if given timestamp buffered"
  [source-buffer timestamp]

  (let [run?    (cljs.core/atom true)
        ?result (cljs.core/atom nil)
        length  (-> source-buffer .-buffered .-length)]
    (dorun
      (for [idx    (range length)
            :while @run?
            :let   [start (-> source-buffer .-buffered (.start idx - 1))
                    end (-> source-buffer .-buffered (.end idx - 1))]]
        (when (and (>= timestamp start)
                   (<= timestamp end))
          (reset! ?result {:start start :end end :index idx})
          (reset! run? false))))
    @?result))


(defn timestamp-percent [timestamp start end]
  (/ (* 100 (- timestamp start))
     (- end start)))

(defn duration-percent [current-timestamp duration]
  (* 100 (/ current-timestamp duration)))

(defn get-byte-chunk [content-size timestamp-percent]
  (js/Math.floor (* (/ content-size 100) timestamp-percent)))

(defn resp->content-range [resp]
  (when-let [content-range (-> resp .-headers (.get "content-range"))]
    (let [range-meta       (s/split content-range " ")
          ranges-with-size (s/split (first (rest range-meta)) "/")
          ranges           (s/split (first ranges-with-size) "-")]
      {:unit  (first range-meta)
       :start (parse-long (first ranges))
       :end   (parse-long (last ranges))
       :size  (parse-long (last ranges-with-size))})))
