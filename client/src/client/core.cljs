(ns client.core
  (:require
   [reagent.core :as r]
   [reagent.dom :as rd]
   [re-frame.core :as rf]
   ["react-dom/client" :refer [createRoot]]
   [clojure.string :as s]
   [cljs.core.async :refer [go]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [client.util.subscription :as subscription]
   [client.util.destruct :refer [destruct]]
   [client.components.player.core.metadata-buffer :as mbuffer]
   [client.components.player.events :as player.events]
   [client.components.player.core.metadata-buffer :refer [lookup-in]]
   [client.components.player.utils :refer [buffered-timestamp?
                                           timestamp-percent]]
   [client.util.event :as util.event]))

(defonce watch-print #(js/console.log (clj->js %)))
(defonce root (createRoot (js/document.getElementById "root")))

(def stream "http://localhost:8000/music/stream")

(defn unmount-source-buffer [media buffer]
  (when buffer
    (.removeSourceBuffer media buffer)))

(defn MediaSource [player {:keys [type duration]
                           :or   {type
                                  ;; "audio/mp4;codecs=mp4a.40.2"
                                  ;; "audio/mpeg"
                                  "audio/webm;codecs=opus"

                                  duration
                                  144
                                  ;; 3024
                                  }}]
  (let [metadataBuffer     (mbuffer/MetaDataBuffer. (atom {}))
        media              (cljs.core/atom nil)
        source-ref         (cljs.core/atom nil)
        source-buffer      (cljs.core/atom nil)
        last-content-range (cljs.core/atom nil)]

    (rf/dispatch-sync [::util.event/assoc-in [:player :stream] "http://localhost:8000/music/stream"])
    
    (subscription/subscribe-atom
      media
      :sourceopen
      (fn [e]
        (let [{media :target} (destruct e)
              -source-buffer  (.addSourceBuffer media type)
              ;; buffer-source (.createBufferSource audio-context)
              audio-context   (js/AudioContext.)
              buffer-source   (.createBufferSource audio-context)]
          
          (reset! source-buffer -source-buffer)
          ;; (set! (.-duration media) duration)
          
          ;; (set! (.-mode -source-buffer) "sequence")

          (set! (.-buffer buffer-source) (.-buffer -source-buffer))
          (.connect buffer-source (.-destination audio-context))

          ;; (set! (.-buffer buffer-source) (.-buffer -source-buffer))
          

          ;; (.connect buffer-source (.-destination audio-context))
          ;; (tap> @player)

          ;; (.connect @player buffer-source)
          
          ;; Segment need to be audio consequent
          ;; If not sourceBuffer will not play
          ;; (set! (.-mode -source-buffer) "segments")
          ;; (rf/dispatch-sync [::player.events/init-player @player])
          
          (rf/dispatch-sync [::util.event/assoc-in [:player :meta-data-buffer] metadataBuffer])
          (rf/dispatch-sync [::util.event/assoc-in [:player :source-buffer] -source-buffer])
          (rf/dispatch-sync [::util.event/assoc-in [:player :ref] @player])
          (rf/dispatch-sync [::util.event/assoc-in [:player :audio-context] audio-context])
          (rf/dispatch-sync [::player.events/range-seeked])
          
          ))
      #js{:once true})

    (subscription/subscribe-atom
      player
      :timeupdate
      (fn []
        (let [duration     (.-duration @player)
              current-time (.-currentTime @player)
              ?buffered    (buffered-timestamp? @source-buffer current-time)
              ?meta-data   (lookup-in metadataBuffer [:timestamp] ?buffered)]
          ;; (tap> @media)
          
          (when (and ?meta-data
                     (not= (-> ?meta-data :timestamp :end) duration))
            (let [start          (-> ?meta-data :timestamp :start)
                  end            (-> ?meta-data :timestamp :end)
                  played-percent (timestamp-percent current-time start end)]
              (when (>= played-percent 80)
                (rf/dispatch [::player.events/range-seeked])))))))
    
    (subscription/subscribe-atom
      player
      :seeking
      (fn []
        (.pause @player)
        (rf/dispatch-sync [::player.events/range-seeked])))

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
             :max 3024
             ;; 144
             :step         1
             :value        @timestamp
             :style        {:width 300 :height 30}
             :on-change    #(reset! timestamp (-> % .-target .-value))
             :on-mouse-up  #(player-change-time @player %)
             :on-touch-end #(player-change-time @player %)}]))

(defn Player []
  (r/with-let [audio (r/atom true)]
    (let [player (cljs.core/atom nil)]
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
