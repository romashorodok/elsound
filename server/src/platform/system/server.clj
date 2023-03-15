(ns platform.system.server
  
  (:require [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [compojure.core :as compojure]
            [ring.util.response :as r]
            [clojure.java.io :as io]
            [platform.system.util.response :as resp]
            [platform.system.util.reader :refer [range-reader]]
            [platform.system.header.range :refer [range-segment]]
            [platform.system.header.range :refer [wrap-range-header]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]])
  (:import [io.lindstrom.mpd MPDParser])
  (:import [platform.system.header.range Range]))

;; From this used tools to parse webm files
;; https://github.com/acolwell/mse-tools
;; https://stackoverflow.com/questions/37786956/media-source-extensions-appendbuffer-of-webm-stream-in-random-order

;; https://stackoverflow.com/questions/59860029/how-to-use-mediarecorder-as-mediasource
;; 
;; ffmpeg -i test.mp3 -acodec libopus -b:a 96K -map 0 -map_metadata 0:s:0 -strict experimental test-transcode.webm

;; ffmpeg -y -i "test.mp3" -c:a aac -b:a 128k -muxdelay 0 -f segment -sc_threshold 0 -segment_time 7 -segment_list "playlist.m3u8" -segment_format mpegts "file%d.m4a"

;; ffmpeg -y -i "test.mp3" -c:a libopus -b:a 128k -muxdelay 0 -f segment -sc_threshold 0 -segment_list "playlist.m3u8" -segment_list_flags live  -segment_list_type hls -hls_segment_type mpegts  -segment_list_flags +live -segment_time 10 -hls_playlist_type vod  -hls_flags single_file  "file%d.webm"

;; ffmpeg -i "test.mp3" -c:v libvpx -threads 4 -vf setdar=dar=0,setsar=sar=0,-b:v 524288E -bufsize 1835k -movflags faststart -keyint_min 96 -g 96 -frame-parallel 1 -f webm -dash 1 -strict -2 out.webm -y

;; -segment_list_size 10 ;; limit playlist.m3u8 file element
;; -segment_wrap 10 ;; when limit playlist may rewrite element

;; Best way to do this

;; ffmpeg -y -i "test.mp3" \
;; -c:a libopus -b:a 128k \
;; -muxdelay 0 \
;; -f dash -dash_segment_type webm -seg_duration 2 -frag_type duration -frag_duration 2 -streaming 1 -ldash 1 -single_file 1 master.mpd

;; ffmpeg -re -i ./test.mp3 \
;; -c:v libx264 -x264-params keyint=120:scenecut=0 -b:v 1M -c:a copy \
;; -f dash -dash_segment_type mp4 \
;; -seg_duration 2 \
;; -target_latency 3 \
;; -frag_type duration \
;; -frag_duration 0.2 \
;; -window_size 10 \
;; -extra_window_size 3 \
;; -streaming 1 \
;; -ldash 1 \
;; -use_template 1 \
;; -use_timeline 0 \
;; -write_prft 1 \
;; -fflags +nobuffer+flush_packets \
;; -format_options "movflags=+cmaf" \
;; master.mpd

;; ffmpeg -re -i ./test.mp3 \
;; -c:a libopus -b:a 128k \
;; -f dash -dash_segment_type webm \
;; -seg_duration 2 \
;; -target_latency 3 \
;; -frag_type duration \
;; -frag_duration 2 \
;; -streaming 1 \
;; -ldash 1 \
;; -use_template 1 \
;; -use_timeline 0 \
;; -write_prft 1 \
;; -fflags +nobuffer+flush_packets \
;; -format_options "movflags=+cmaf" \
;; master.mpd

;; ffmpeg -f webm_dash_manifest -i test.webm \
;; -map 0 \
;; -c copy \
;; -f webm_dash_manifest \
;; -adaptation_sets "id=0,streams=0" \
;; manifest.xml

(def filename
  ;; "test-transcode.opus"
  ;; "test-transcode.ogg"
  ;; "test-transcode.m4a"
  ;; "test-transcode.webm"
  ;; "test-transcode.mp3"
  ;; "file0.webm"
  "test.mp3"
  ;; "output_test.mp3"
  ;; "master-stream0.webm"
  )

(def filepath
  (io/file (io/file (str (System/getProperty "user.dir") "/" filename))))

;; TODO: Calculate audio duration.
;; https://stackoverflow.com/a/20716463

(defn mpd-parse [mpd-file]
  (let [stream (io/input-stream mpd-file)
        parser (MPDParser.)
        ranges (.parse parser stream)]
    (let [adaptationSets (->> (iterator-seq (.iterator (.getPeriods ranges)))
                              (mapcat #(.getAdaptationSets %)))
          segmentList    (->> (mapcat #( .getRepresentations  %) adaptationSets)
                              (map #(.getSegmentList %)))]
      (let [metaDataSegment (map #(-> %
                                      .getInitialization
                                      .getRange)
                                 segmentList)
            ranges          (->> (mapcat #(-> % .getSegmentURLs) segmentList)
                                 (map #(.getMediaRange %)))]
        (cons (first metaDataSegment) ranges)))))

(defn find-segment [segments byte-range]
  (filter
    (fn [segment]
      (let [segment-range (range-segment segment)]
        (and (>= byte-range (:range-start segment-range))
             (<= byte-range (:range-end segment-range)))))
    segments))

;; NOTE: when use audio tag in chrome. It's stop audio when it's fully loaded.
;; It's may be resolved by disabling audio preload 
(defn stream-music [{:keys [^Range range-header]}]
  (prn range-header)
  (let [segment (range-segment (first (find-segment (mpd-parse "master.mpd") (:range-start range-header))))
        reader  (range-reader filepath segment)]
    (resp/range-reader->response reader)))

(defn mpd-test [r]
  (let [input   26948
        segment (find-segment (mpd-parse "master.mpd") input)]
    segment
    ;; (r/response
    ;;   segment
    ;;   )
    ))

(compojure/defroutes music-routes
  (compojure/GET "/test" _ mpd-test)
  (compojure/GET "/stream" _ stream-music))

(compojure/defroutes app-routes
  (compojure/context "/music" _ music-routes))

(def cors-headers
  {"Access-Control-Allow-Origin"      "*"
   "Access-Control-Allow-Methods"     "GET, POST, PUT, DELETE, OPTIONS"
   "Access-Control-Allow-Headers"     "*"
   "Access-Control-Allow-Credentials" "true"
   "Access-Control-Expose-Headers"    "Range,Content-Range"})

(defn preflight? [request]
  (= (request :request-method) :options))

(defn wrap-cors [handler]
  (fn [request]
    (try
      
      (if (preflight? request)
        {:status  200
         :headers cors-headers
         :body "test"}
        (-> (handler request)
            (update-in [:headers] merge cors-headers)))
      (catch Exception e
        (prn e)))))

(defn wrap-not-found [handler]
  (fn [request]
    (let [response (handler request)]
      (case response
        nil (r/bad-request {:msg "Not found route"})
        response))))

(defmethod ig/init-key ::handler [_ _]
  (-> app-routes
      wrap-reload
      wrap-params
      wrap-not-found
      wrap-json-response
      wrap-range-header
      wrap-cors
      (wrap-json-body {:keywords? true})))

(defmethod ig/init-key ::jetty [_ {:keys [handler] :as config}]
  (jetty/run-jetty handler config))

(defmethod ig/halt-key! ::jetty [_ server]
  (.stop server))

