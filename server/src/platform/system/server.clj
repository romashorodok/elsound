(ns platform.system.server
  (:refer-clojure :exclude [range chunk])
  (:require [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [compojure.core :as compojure]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.core :refer [quot]]
            [ring.util.response :as r]
            [ring.util.io :as rio]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]])
  (:import [java.nio.file Files]
           [java.io File]))

(def filename
  "test.mp3")

(defn get-file [filename]
  (let [file (io/file (str (System/getProperty "user.dir") "/" filename))]
    file))

(defn get-size [^File file]
  (let [path (.toPath file)]
    (Files/size path)))

;; (defn blurp [f]
;;   (let [dest (java.io.ByteArrayOutputStream.)]
;;     (with-open [src (io/input-stream f)]
;;       (io/copy src dest))
;;     (.toByteArray dest)))

(defn read-bytes [start chunk-end]
  (let [file (get-file filename)]
    (with-open [stream (io/input-stream file)]
      (.skip stream start)
      (.readNBytes stream (+ (- chunk-end start) 1)))))

(defprotocol Header
  (->header [this]
    "Transform protocol object to HTTP header string"))

(defrecord Content-Range
    [unit
     range-start
     range-end
     size])

(extend-type Content-Range
  Header
  (->header [^Content-Range {:keys [unit range-start range-end size]}]
    (str unit " " range-start "-" range-end "/" size)))

(defn ^Content-Range content-range [{:keys [unit range-start range-end size]}]
  (->Content-Range unit range-start range-end size))

(defn partial-content-response [chunk-length ^Content-Range range body]
  (-> (r/response body)
      (r/header "Content-Range" (->header range))
      (r/header "Content-Length" chunk-length)
      (r/header "Accept-Ranges" (:unit range))
      (r/header "Connection" "close")
      (r/status 206)))

(defn get-chunk [file-size chunk-size chunk-start]
  (let [chunk-end (+ chunk-start chunk-size)]
    (if (>= chunk-end file-size)
      (let [end-diff (- file-size chunk-end)]
        (+ chunk-end end-diff))
      chunk-end)))

;; NOTE: when use audio tag in chrome. It's stop audio when it's fully loaded.
;; It's may be resolved by disabling audio preload 
(defn stream-music [{:keys [range-header]}]
  (let [file-size    (get-size (get-file filename))
        chunk-size   (quot file-size 100)
        chunk-start  (:range-start range-header)
        chunk-end    (get-chunk file-size chunk-size chunk-start)
        chunk-length (- chunk-end chunk-start)]
    (partial-content-response
      chunk-length
      (content-range {:unit        (:unit range-header)
                      :range-start chunk-start
                      :range-end   chunk-end
                      :size        file-size})
      (read-bytes chunk-start chunk-end)))
  
  ;; TODO: look at this and try apply it somehow
  ;; (rio/piped-input-stream
    ;; (fn [resp]
      ;; (spit resp  (blurp filename))))
  )

(compojure/defroutes music-routes
  (compojure/GET "/stream" _ stream-music))

(compojure/defroutes app-routes
  (compojure/context "/music" _ music-routes))

(defn wrap-not-found [handler]
  (fn [request]
    (let [response (handler request)]
      (case response
        nil (r/bad-request {:msg "Not found route"})
        response))))

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
                      range-end)]
    (->Range unit range-start range-end)))

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

(defmethod ig/init-key ::handler [_ _]
  (-> app-routes
      wrap-reload
      wrap-params
      wrap-not-found
      wrap-json-response
      wrap-range-header
      (wrap-json-body {:keywords? true})))

(defmethod ig/init-key ::jetty [_ {:keys [handler] :as config}]
  (jetty/run-jetty handler config))

(defmethod ig/halt-key! ::jetty [_ server]
  (.stop server))

