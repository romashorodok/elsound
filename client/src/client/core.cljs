(ns client.core
  (:require
   [reagent.core :as r]
   [reagent.dom :as rd]
   ["react-native" :as rn]
   ["react-dom/client" :refer [createRoot]]))

(defonce watch-print #(js/console.log (clj->js %)))
(defonce root (createRoot (js/document.getElementById "root")))

(defonce splash-img (js/require "../assets/shadow-cljs.png"))

(def styles
  ^js (-> {:container
           {:flex            1
            :backgroundColor "#fff"
            :alignItems      "center"
            :justifyContent  "center"}
           :title
           {:fontWeight "bold"
            :fontSize   24
            :color      "blue"}}
          (clj->js)
          (rn/StyleSheet.create)))

(defn render-root []
  [:> rn/View {:style (.-container styles)}
   [:> rn/Text {:style (.-title styles)} "Hello world!"]
   [:> rn/Image {:source splash-img :style {:width 200 :height 200}}]])

(defn ^:dev/after-load start []
  (tap> root)
  (.render root (r/as-element [render-root])))

(defn init! []
  (add-tap watch-print)
  (enable-console-print!)
  (start))
