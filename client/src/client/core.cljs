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
     timestamp-start
     timestamp-end])

(defn subscribe [^js/EventTarget target type fn opts]
  (.addEventListener target (key->js type) fn opts))

;; Should it be better like macro ?
;; And not store by keyword
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
       :start (parse-long (first ranges))
       :end   (parse-long (last ranges))
       :size  (parse-long (last ranges-with-size))})))

(defn fetch-buffer [{:keys [url header]}]
  (-> (js/fetch url (clj->js {:headers header}))
      (.then #(hash-map :content-range (header->content-range %)
                        :buffer  (.arrayBuffer %)))))

(defn timestamp->buffered-range [buffer timestamp]
  (let [run?   (cljs.core/atom true)
        ?result (cljs.core/atom nil)
        length  (-> buffer .-buffered .-length)]
    (dorun
      (for [idx    (range length)
            :while (or @run?
                       (> idx ))
            :let   [start (-> buffer .-buffered (.start idx - 1))
                    end (-> buffer .-buffered (.end idx - 1))]]
        (when (and (>= timestamp start)
                   (<= timestamp end))
          (reset! ?result {:start start :end end :index idx})
          (reset! run? false))))
    @?result))


(defprotocol Store
  (consume [this data])
  (keep-sorted [this])
  (append [this data]))

(defprotocol IRangeMetaData
  (-consumed-ranges-lookup [this meta-data]))

(deftype RangeMetaDataStore [store]
  IRangeMetaData
  (-consumed-ranges-lookup [this ^MetaData meta-data]
    (tap> meta-data))
  
  Store
  (consume [this ^MetaData meta-data]
    )
  (append [_ ^MetaData meta-data]
    (let [idx (count @store)]
      (swap! store conj {idx meta-data})))
  (keep-sorted [_]
    )
  
  IDeref
  (-deref [_] store))

(defn seeking [player media last-content-range]
  (.pause @player)
  (let [source-buffer     (first (.-sourceBuffers media))
        timestamp-persent (* 100 (/ (.-currentTime @player)
                                    (.-duration @player)))
        ;; TODO make global audio size
        request-chunk     (-> (* (/ (-> @last-content-range
                                        :size)
                                    100)
                                 timestamp-persent)
                              js/Math.floor)
        ?playing-range    (timestamp->buffered-range
                            source-buffer
                            (.-currentTime @player))]
    
    (.abort source-buffer)
    (set! (.-timestampOffset source-buffer)
          (.-currentTime @player))

    (go
      (let [range-req  (str "bytes=" request-chunk "-")
            resp       (<p! (fetch-buffer
                              {:url    stream
                               :header {:range range-req}}))
            buffer-end (-> resp :content-range :end)]
        
        (reset! last-content-range (:content-range resp))
        (set! (.-appendWindowEnd source-buffer) buffer-end)
        (.appendBuffer source-buffer (<p! (:buffer resp)))))))

(defn MediaSource [player {:keys [type duration]
                           :or   {type "audio/mpeg" duration 144}}]
  (let [store              (RangeMetaDataStore. (atom {}))
        media              (js/MediaSource.)
        source-ref         (cljs.core/atom nil)
        source-buffer      (cljs.core/atom nil)
        last-content-range (cljs.core/atom nil)]

    (subscribe
      media
      :sourceopen
      (fn [e]
        (let [{media :target} (destruct e)
              -source-buffer  (.addSourceBuffer media type)]
          (reset! source-buffer -source-buffer)
          (set! (.-duration media) duration)
          
          (go
            (let [resp       (<p! (fetch-buffer {:url    stream
                                                 :header {:range "bytes=0-"}}))
                  buffer-end (-> resp :content-range :end)]
              (reset! last-content-range (:content-range resp))
              (set! (.-appendWindowEnd -source-buffer) buffer-end)
              (.appendBuffer -source-buffer (<p! (:buffer resp)))))))
      
      #js{:once true})

    (subscribe-option! source-buffer :update
                       (fn [e]
                         (let [{source-buffer :target} (destruct e)
                               content-range           @last-content-range
                               playing-range           (timestamp->buffered-range
                                                         source-buffer
                                                         (.-currentTime @player))]

                           (if false
                             (tap> "Check if ranges same by index in store. If not range was merged")
                             (append store
                                     (map->MetaData
                                       {:byte-start      (:start content-range)
                                        :byte-end        (:end content-range)
                                        :timestamp-start (:start playing-range)
                                        :timestamp-end   (:end playing-range)})))
                           
                           ;; (tap> @@store)
                           )))


    (subscribe-option! player :seeking #(seeking player media last-content-range))
    
    (r/create-class
      {:component-did-mount
       #(set! (.-src @source-ref) (js/URL.createObjectURL media))
       
       :component-will-unmount
       #(unmount-source-buffer media @source-buffer)
       
       :reagent-render
       (fn [] [:source {:ref #(reset! source-ref %)}])})))

(defn player-change-time [player event]
  (let [time (-> event .-target .-value parse-long)]
    (set! (.-currentTime player) time)))

(defn PlayerControl [player]
  (r/with-let [timestamp (r/atom 0)]
    
    (subscribe-option! player :timeupdate
                       #(reset! timestamp (-> % .-target .-currentTime)))
    
    [:input {:type         :range
             :min          0
             :max          144
             :step         1
             :value        @timestamp
             :on-change    #(reset! timestamp (-> % .-target .-value))
             :on-mouse-up  #(player-change-time @player %)
             :on-touch-end #(player-change-time @player %)}]))

(defn Player []
  (r/with-let [audio (r/atom true)]
    (let [player         (cljs.core/atom nil)]
      [:<>
       (when @audio
         [:<>
          [:audio {:controls true :ref #(reset! player %)}
           [MediaSource player]]])
       
       [PlayerControl player]
       
       [:button {:on-click #(swap! audio not)} "Change audio"]])))

(defn render-root []
  [:<>
   [Player]])

(defn ^:dev/after-load start []
  (.render root (r/as-element [render-root])))

(defn init! []
  (add-tap watch-print)
  (enable-console-print!)
  (start))
