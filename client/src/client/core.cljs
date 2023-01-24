(ns client.core
  (:refer-clojure :exclude [get])
  (:require
   [reagent.core :as r]
   [reagent.dom :as rd]
   [goog.object :refer [get]]
   ["react-dom/client" :refer [createRoot]]
   [clojure.string :as s]
   [cljs.core.async :refer [go]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [client.util.subscription :as subscription]))

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

;; NOTE: is it butter for meta data? But need make it like record
#:byte{:start 0}
#:timestamp{:start 0}

(defrecord MetaData
    [byte-start
     byte-end
     timestamp-start
     timestamp-end])

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
  (let [run?    (cljs.core/atom true)
        ?result (cljs.core/atom nil)
        length  (-> buffer .-buffered .-length)]
    (dorun
      (for [idx    (range length)
            :while @run?
            :let   [start (-> buffer .-buffered (.start idx - 1))
                    end (-> buffer .-buffered (.end idx - 1))]]
        (when (and (>= timestamp start)
                   (<= timestamp end))
          (reset! ?result {:start start :end end :index idx})
          (reset! run? false))))
    @?result))


(defprotocol Store
  (-append [this data]))

(defprotocol IRangeMetaData
  (lookup-range [this {:keys [start end]}])
  (consume-ranges-for [this {:keys [start end]}])

  (latest-range [_ ranges range])
  (earlies-range [_ ranges range])

  (union-ranges [this range])
  (add-range [thid range])
  (keep-sorted [this]))


;; NOTE: Should be better naming for that ?
(defn consume-start? [[_ range?] requested-range]
  (<= (:timestamp-start range?) (:end requested-range)))
(defn consume-end? [[_ range?] requested-range]
  (>= (:timestamp-end range?) (:start requested-range)))


(defn latest-range? [[_ range?] target-range]
  (<= (:end target-range) (:timestamp-end range?) ))

(defn earlies-range? [[_ range?] target-range]
  (>= (:start target-range) (:timestamp-start range?)))

(defn from-earlies-latest->range [earlies latest]
  {:byte-start      (:byte-start earlies),
   :byte-end        (:byte-end latest),
   :timestamp-start (:timestamp-start earlies),
   :timestamp-end   (:timestamp-end latest)})

(deftype RangeMetaDataStore [store]
  IRangeMetaData
  (lookup-range [this {:keys [start end]}]
    (let [run?    (cljs.core/atom true)
          ?result (cljs.core/atom nil)
          store   @store]
      (dorun
        (for [idx    (range (count store))
              :while @run?
              :let   [range? (get-in store [idx])
                      timestamp-start (:timestamp-start range?)
                      timestamp-end (:timestamp-end range?)]]
          (when (and (>= start timestamp-start)
                     (<= end timestamp-end))
            (reset! ?result (assoc range? :index idx))
            (reset! run? false))))
      @?result))

  (union-ranges [_ {:keys [start end] :as range}]
    (->> @store
         (filter #(consume-start? % range))
         (filter #(consume-end? % range))
         (into {})))

  (latest-range [_ ranges range]
    (->> ranges
         (filter #(latest-range? %  range))
         first
         next
         (into {})))
  
  (earlies-range [_ ranges range]
    (->> ranges
         (filter #(earlies-range? % range))
         first
         next
         (into {})))

  (keep-sorted [this]
    (let [state         @store
          nil-ids-items (mapv (fn [[_ val]] {nil val}) state)
          sort-items    #(sort-by (fn [item]
                                    (get-in item [nil :timestamp-start])) %)
          index-items   #(map-indexed (fn [idx item]
                                        {idx (get-in item [nil])}) %)]
      (swap! store #(into {} (-> nil-ids-items
                                 sort-items
                                 index-items)))))
  
  (add-range [this range]
    (-append this range)
    
    (let [range {:start (:timestamp-start range) :end (:timestamp-end range)} 
          union (union-ranges this range)]
      (if (> (count union) 1)
        (let [latest  (latest-range this union range)
              earlies (earlies-range this union range)
              unified (from-earlies-latest->range earlies latest)]
          (swap! store
                 (fn [state]
                   (let [ids           (keys union)
                         pure          (apply dissoc state ids)
                         nil-ids-items (mapv
                                         (fn [[_ val]]
                                           {nil val})
                                         (cons [nil unified] (seq pure)))
                         sorted-items  (sort-by
                                         (fn [item]
                                           (get-in item [nil :timestamp-start]) )
                                         nil-ids-items)]
                     (into {} (map-indexed
                                (fn [idx item]
                                  {idx (get-in item [nil])})
                                sorted-items))))))
        (keep-sorted this))))
  
  Store
  (-append [_ ^MetaData meta-data]
    (let [idx (count @store)]
      (swap! store conj {idx meta-data})))
  
  IDeref
  (-deref [_] store))

(defn request? [percent]
  (let [request-on 95]
    (>= percent request-on)))

(defn range-percent [current-time {:keys [start end]}]
  (/ (* 100 (- current-time start))
     (- end start)))

(defn seeking [player media last-content-range store]
  (let [source-buffer           (first (.-sourceBuffers @media))
        ?buffered-playing-range (timestamp->buffered-range
                                  source-buffer
                                  (.-currentTime @player))]
    
    (if ?buffered-playing-range
      (if-let [range (lookup-range store ?buffered-playing-range)]
        (let [range-percent (range-percent (.-currentTime @player)
                                           {:start (:timestamp-start range)
                                            :end   (:timestamp-end range)})]
          (.abort source-buffer)
          (when (request? range-percent)
            (set! (.-timestampOffset source-buffer)
                  (:timestamp-end range))
            (go
              (let [range-header (str "bytes=" (inc (:byte-end range)) "-")
                    resp         (<p! (fetch-buffer
                                        {:url    stream
                                         :header {:range range-header}}))
                    buffer-end   (-> resp :content-range :end)]
                (reset! last-content-range (:content-range resp))
                (set! (.-appendWindowEnd source-buffer) buffer-end)
                (.appendBuffer source-buffer (<p! (:buffer resp)))))))
        (ex-info "Inconsistent store and buffer on end of chunk request"
                 {:store    @@store
                  :buffered ?buffered-playing-range
                  :stored   range}))
      (let [timestamp-percent (* 100 (/ (.-currentTime @player)
                                        (.-duration @player)))
            request-chunk     (-> (* (/ (-> @last-content-range
                                            :size)
                                        100)
                                     timestamp-percent)
                                  js/Math.floor)]
        (.abort source-buffer)
        (set! (.-timestampOffset source-buffer) (.-currentTime @player))
        (go
          (let [range-req  (str "bytes=" request-chunk "-")
                resp       (<p! (fetch-buffer
                                  {:url    stream
                                   :header {:range range-req}}))
                buffer-end (-> resp :content-range :end)]
            
            (reset! last-content-range (:content-range resp))
            (set! (.-appendWindowEnd source-buffer) buffer-end)
            (.appendBuffer source-buffer (<p! (:buffer resp)))))))))

(defn MediaSource [player {:keys [type duration]
                           :or   {type "audio/mpeg" duration 144}}]
  (let [store              (RangeMetaDataStore. (atom {}))
        media              (cljs.core/atom nil)
        source-ref         (cljs.core/atom nil)
        source-buffer      (cljs.core/atom nil)
        last-content-range (cljs.core/atom nil)]
    
    (subscription/subscribe-atom
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
    
    (subscription/subscribe-atom
      source-buffer
      :update
      (fn [e]
        (let [{source-buffer :target} (destruct e)
              content-range           @last-content-range
              playing-range           (timestamp->buffered-range
                                        source-buffer
                                        (.-currentTime @player))]
          (add-range store
                     (map->MetaData
                       {:byte-start      (:start content-range)
                        :byte-end        (:end content-range)
                        :timestamp-start (:start playing-range)
                        :timestamp-end   (:end playing-range)}))
          
          (tap> @@store))))

    (subscription/subscribe-atom
      player
      :timeupdate
      (fn fetch-next [event]
        (comment
          ;; Example of how to unsubscribe
          (subscription/unsubscribe @player :timeupdate fetch-next))
        
        
        ;; NOTE: Does it good case for channels use ? Or workers ? Or both ?
        (let [source-buffer           @source-buffer
              ?buffered-playing-range (timestamp->buffered-range
                                        source-buffer
                                        (.-currentTime @player))]
          (if-let [range (lookup-range store ?buffered-playing-range)]
            (when (not= (:timestamp-end range) (.-duration @player))
              (let [range-percent (range-percent (.-currentTime @player)
                                                 {:start (:timestamp-start range)
                                                  :end   (:timestamp-end range)})]
                (.abort source-buffer)
                (when (request? range-percent)
                  (set! (.-timestampOffset source-buffer)
                        (:timestamp-end range))
                  (go
                    (let [range-header (str "bytes=" (inc (:byte-end range)) "-")
                          resp         (<p! (fetch-buffer
                                              {:url    stream
                                               :header {:range range-header}}))
                          buffer-end   (-> resp :content-range :end)]
                      (reset! last-content-range (:content-range resp))
                      (set! (.-appendWindowEnd source-buffer) buffer-end)
                      (.appendBuffer source-buffer (<p! (:buffer resp))))))))
            (ex-info "Inconsistent store and buffer on end of chunk request"
                     {:store    @@store
                      :buffered ?buffered-playing-range
                      :stored   range})))))
    
    (subscription/subscribe-atom
      player
      :seeking
      (fn []
        (.pause @player)
        (seeking player media last-content-range store)))

    (r/create-class
      {:component-did-mount
       #(set! (.-src @source-ref) (js/URL.createObjectURL @media))
       
       :component-will-unmount
       #(unmount-source-buffer @media @source-buffer)
       
       :reagent-render
       (fn [] [:source {:ref #(do
                               (reset! media (js/MediaSource.))
                               (reset! source-ref %))}])})))

(defn player-change-time [player event]
  (let [time (-> event .-target .-value parse-long)]
    (set! (.-currentTime player) time)))

;; TODO: make it better
(defn draw-canvas [-player -canvas -ctx]
  (let [player   @-player
        canvas   @-canvas
        ctx      -ctx
        duration (.-duration player)
        buffer   (.-buffered player)
        length   (.-length buffer)
        width    (.-width canvas)
        height   (.-height canvas)]
    (set! (.-fillStyle ctx) "#000")
    (.fillRect ctx 0 0 width height)
    (set! (.-fillStyle ctx) "#d00")
    
    (dorun 
      (for [idx  (range length)
            :let [start (* (/ (.start buffer idx) duration) width)
                  end   (* (/ (.end buffer idx) duration) width)]]
        (.fillRect ctx start 0 (- end start) height)))

    (set! (.-fillStyle ctx) "#fff")
    
    (set! (.-textBaseLine ctx) "top")
    (set! (.-textAlign ctx) "left")
    (.fillText ctx  (.toFixed (.-currentTime player) 1) 10 15)

    (set! (.-textAlign ctx) "right")
    (.fillText ctx (.toFixed duration) (- width 10) 15)

    (.beginPath ctx)
    (.arc ctx
          (* width (/ (.-currentTime player) duration))
          (* height 0.5)
          7
          0
          (* 2 js/Math.PI))
    (.fill ctx)
    
    (js/setTimeout #(draw-canvas -player -canvas -ctx) 29)))

(defn PlayerCanvas [player]
  (let [ref  (cljs.core/atom nil)
        ctx  (cljs.core/atom nil)
        draw #(draw-canvas player ref %)]
    
    (add-watch ref :canvas-ref #(if @ref
                                  (when-let [-ctx (.getContext @ref "2d")]
                                    (if @player
                                      (.addEventListener @player "progress"
                                                         (fn [] (draw -ctx)))))
                                  ;; Remove listener
                                  nil))

    
    [:canvas {:width 300 :height 30 :ref #(reset! ref %)}]))

(defn PlayerControl [player]
  (r/with-let [timestamp (r/atom 0)]

    (subscription/subscribe-atom
      player
      :timeupdate
      #(reset! timestamp (-> % .-target .-currentTime)))
    
    [:input {:type         :range
             :min          0
             :max          144
             :step         1
             :value        @timestamp
             :style        {:width 300 :height 30}
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
           [MediaSource player]]
          [PlayerCanvas player]
          [PlayerControl player]])
       
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
