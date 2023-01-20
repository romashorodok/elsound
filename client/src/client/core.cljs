(ns client.core
  (:refer-clojure :exclude [get])
  (:require
   [reagent.core :as r]
   [reagent.dom :as rd]
   [goog.object :refer [get]]
   ["react-dom/client" :refer [createRoot]]))

(defn destruct [o]
  (reify
    ILookup
    (-lookup [_ k]
      (get o (name k)))
    (-lookup [_ k not-found]
      (get o (name k) not-found))))

;; Utils 

(defonce watch-print #(js/console.log (clj->js %)))
(defonce root (createRoot (js/document.getElementById "root")))

(def stream "http://localhost:8000/music/stream")

(defrecord MetaData
    [byte-start
     byte-end
     buffer-start
     buffer-end])

(defn subscribe [^js/EventTarget target type fn opts]
  (.addEventListener target (key->js type) fn opts))

(defn unmount-source-buffer [media buffer]
  (when buffer
    (.removeSourceBuffer media buffer)))

(defn MediaSource [{:keys [type] :or {type "audio/mpeg"}}]
  (let [media         (js/MediaSource.)
        source-ref    (cljs.core/atom nil)
        source-buffer (cljs.core/atom nil)]
    
    (add-watch source-buffer :test #(tap> @source-buffer))

    (subscribe media :sourceopen
               (fn [e]
                 (let [{media :target} (destruct e)]
                   (reset! source-buffer (.addSourceBuffer media type))))
               #js{:once true})
    
    (r/create-class
      {:component-did-mount
       #(set! (.-src @source-ref) (js/URL.createObjectURL media))
       
       :component-will-unmount
       #(unmount-source-buffer media @source-buffer)
       
       :reagent-render
       (fn [] [:source {:ref #(reset! source-ref %)}])})))

(defn Player []
  (r/with-let [audio (r/atom true)]
    [:<>
     (if @audio
       [:audio
        [MediaSource audio]])
     [:button {:on-click #(swap! audio not)} "Change audio"]]))

(defn render-root []
  [:<>
   [Player]
   [:input {:type         :range
            :on-mouse-up  #(prn "Hello world")
            :on-touch-end #(prn "From touch")}]])

(defn ^:dev/after-load start []
  (.render root (r/as-element [render-root])))

(defn init! []
  (add-tap watch-print)
  (enable-console-print!)
  (start))
