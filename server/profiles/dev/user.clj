(ns user
  (:require [integrant.repl :refer [go reset halt set-prep!]]
            [integrant.core :as ig]
            [lambdaisland.classpath.watch-deps :as watch-deps]
            [vendor.prism :as prism]
            [aero.core :as aero]
            [clojure.tools.namespace.repl :as repl]))

(add-tap (bound-fn* clojure.pprint/pprint))

(watch-deps/start! {:aliases [:dev]})

(def watch-folders ["src"])

(defn watch-source []
  (doseq [folder watch-folders]
    ;; Taken from
    ;; https://github.com/aphyr/prism/blob/master/src/com/aphyr/prism.clj#L94
    (prism/watch!
      folder
      (fn [n]
        ;; n - is namespace of file
        ;; Here can be any type of callback
        n))))

(watch-source)

;; Tell aero reader to know what to do when read #ig/ref in config
(defmethod aero/reader 'ig/ref [{:as _opts :keys [profile]} tag value]
  (ig/ref value))

(defn read-config [config profile]
  (aero/read-config (clojure.java.io/resource config) {:profile profile}))

(defn dev-prep! []
  (let [config "resources/system.edn"]
    (set-prep! (fn []
                 (let [config (-> (read-config config :dev)
                                   (dissoc :profile))]
                   (ig/load-namespaces config)
                   (ig/prep config))))))

(dev-prep!)

(comment
  (go)
  (reset)
  (halt))

