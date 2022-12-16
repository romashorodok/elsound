(ns platform.system.server
  (:require [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [compojure.core :as compojure]
            [ring.util.response :as r]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]))

(defn hello-world-handler [req]
  (r/response {:test "testes t"}))

(compojure/defroutes hello-world
  (compojure/GET "/" _ hello-world-handler))

(compojure/defroutes app-routes
  (compojure/context "/hello-world" _ hello-world))

(def app
  (-> app-routes
      wrap-reload
      wrap-params
      wrap-json-response
      (wrap-json-body {:keywords? true})))

(defmethod ig/init-key ::jetty [_ config]
  (jetty/run-jetty app config))

(defmethod ig/halt-key! ::jetty [_ server]
  (.stop server))

