(ns client.core
  (:require
   [reagent.core :as r]
   [reagent.dom :as rd]
   ["react-dom/client" :refer [createRoot]]))

(defonce watch-print #(js/console.log (clj->js %)))
(defonce root (createRoot (js/document.getElementById "root")))

(defn render-root []
  [:<>
   [:h1.text-3xl.font-bold.underline
    "Hello world"]
   [:button.bg-violet-500.hover:bg-violet-600.active:bg-violet-700.focus:outline-none.focus:ring.focus:ring-violet-300.test-transition.bg-sky-500
    "Some button"]])

(defn ^:dev/after-load start []
  (.render root (r/as-element [render-root])))

(defn init! []
  (add-tap watch-print)
  (enable-console-print!)
  (start))
