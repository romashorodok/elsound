(ns platform.system.header.content-range
  (:require [platform.system.header.protocol :as proto])
  (:gen-class
   :name platform.system.header.content_range.Content-Range))

(defrecord Content-Range
    [unit
     range-start
     range-end
     size])

(extend-type Content-Range
  proto/Header
  (->header [^Content-Range {:keys [unit range-start range-end size]}]
    (str unit " " range-start "-" range-end "/" size)))

(defn ^Content-Range content-range [{:keys [unit range-start range-end size]}]
  (->Content-Range unit range-start range-end size))
