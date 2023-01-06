(ns platform.system.util.response
  (:require [ring.util.response :as r]
            [platform.system.header.protocol :refer [->header]]
            [platform.system.header.range]
            [platform.system.header.content-range :refer [content-range]]
            [platform.system.util.reader :refer [chunk-start
                                                 chunk-end
                                                 chunk-length
                                                 file-size
                                                 read->bytes
                                                 unit]])
  (:import [platform.system.header.range Range]))

(defn partial-content-response [^Range range chunk-length body]
  (-> (r/response body)
      (r/header "Content-Range" (->header range))
      (r/header "Content-Length" chunk-length)
      (r/header "Accept-Ranges" (:unit range))
      (r/header "Connection" "close")
      (r/status 206)))

(defn range-reader->response [range-reader]
  (let [content-range (content-range
                        {:unit        (unit range-reader)
                         :range-start (chunk-start range-reader)
                         :range-end   (chunk-end range-reader)
                         :size        (file-size range-reader)})]
    (partial-content-response
      content-range
      (chunk-length range-reader)
      (read->bytes range-reader))))
