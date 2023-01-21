(ns client.core
  (:refer-clojure :exclude [get])
  (:require
   [reagent.core :as r]
   [reagent.dom :as rd]
   [goog.object :refer [get]]
   ["react-dom/client" :refer [createRoot]]
   [clojure.string :as s]
   [cljs.core.async :refer [go]]
   [cljs.core.async.interop :refer-macros [<p!]]))

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

;; Should it be better like macro ?
(def subscribe-option!-history (atom nil))
(defn subscribe-option!
  ([^Atom target type fn]
   (subscribe-option! target type fn {}))
  ([^Atom target type fn opts]
   (add-watch target (keyword type)
              #(do
                 (when-let [previous (get-in @subscribe-option!-history [(keyword type)])]
                   (.removeEventListener previous (key->js type) fn))
                 (swap! subscribe-option!-history merge {(keyword type) %4})
                 
                 (when-let [target @target]
                   (subscribe target type fn opts))))))

(defn unmount-source-buffer [media buffer]
  (when buffer
    (.removeSourceBuffer media buffer)))

(defn header->content-range [resp]
  (when-let [content-range (-> resp .-headers (.get "content-range"))]
    (let [range-meta       (s/split content-range " ")
          ranges-with-size (s/split (first (rest range-meta)) "/")
          ranges           (s/split (first ranges-with-size) "-")]
      {:unit  (first range-meta)
       :start (first ranges)
       :end   (last ranges)
       :size  (last ranges-with-size)})))

(defn fetch-buffer [{:keys [url header]}]
  (-> (js/fetch url (clj->js {:headers header}))
      (.then #(hash-map :content-range (header->content-range %)
                        :buffer  (.arrayBuffer %)))))

(defn MediaSource [{:keys [type] :or {type "audio/mpeg"}}]
  (let [media         (js/MediaSource.)
        source-ref    (cljs.core/atom nil)
        source-buffer (cljs.core/atom nil)]

    (subscribe media :sourceopen
               (fn [e]
                 (let [{media :target} (destruct e)
                       -source-buffer  (.addSourceBuffer media type)]
                   (reset! source-buffer -source-buffer)
                   (go
                     (let [resp       (<p! (fetch-buffer {:url stream :header {:range "bytes=0-"}}))
                           buffer-end (-> resp :content-range :end)]
                       (set! (.-appendWindowEnd -source-buffer) buffer-end)
                       (.appendBuffer -source-buffer (<p! (:buffer resp)))))))
               
               #js{:once true})

    (subscribe-option! source-buffer :update
                       (fn [e]
                         (let [{source-buffer :target} (destruct e)]
                           (tap> source-buffer))))
    
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
       [:audio {:controls true}
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
