(ns platform.system.server
  (:require [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [compojure.core :as compojure]
            [ring.util.response :as r]
            [clojure.java.io :as io]
            [platform.system.util.response :as resp]
            [platform.system.util.reader :refer [range-reader]]
            [platform.system.header.range :refer [wrap-range-header]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]])
  (:import [platform.system.header.range Range]))

(def filename
  ;; "test.mp3"
  )

(def filepath
  (io/file (io/file (str (System/getProperty "user.dir") "/" filename))))

;; TODO: Calculate audio duration.
;; https://stackoverflow.com/a/20716463

;; NOTE: when use audio tag in chrome. It's stop audio when it's fully loaded.
;; It's may be resolved by disabling audio preload 
(defn stream-music [{:keys [^Range range-header]}]
  (let [reader (range-reader filepath range-header)]
    (resp/range-reader->response reader)))

(compojure/defroutes music-routes
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

