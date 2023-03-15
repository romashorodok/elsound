(ns client.components.player.events
  (:refer-clojure :exclude [byte])
  (:require [cljs.core.async :refer [go take]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [re-frame.core :as rf]
            [client.components.player.injectors :as player.injectors]
            [client.util.subscription :refer [subscribe]]
            [client.util.event :as util.event]
            [client.components.player.utils :refer [buffered-timestamp?
                                                    timestamp-percent
                                                    duration-percent
                                                    get-byte-chunk
                                                    resp->content-range]]
            [client.components.player.core.metadata-buffer :refer [meta-data
                                                                   lookup-in
                                                                   add]]))

(rf/reg-event-fx
  ::add-meta-data
  [(rf/inject-cofx ::player.injectors/meta-data-buffer)
   (rf/inject-cofx ::player.injectors/source-buffer)
   (rf/inject-cofx ::player.injectors/player)]
  (fn [{db :db {:keys [meta-data-buffer source-buffer player]} :coeffects} [_ content-range]]
    (let [current-time        (.-currentTime player) 
          ?buffered-timestamp (buffered-timestamp? source-buffer current-time)
          meta-data           (meta-data {:start (:start ?buffered-timestamp)
                                          :end   (:end ?buffered-timestamp)}
                                         {:start (:start content-range)
                                          :end   (:end content-range)})]
      (if ?buffered-timestamp
        (add meta-data-buffer meta-data)
        (js/console.error "Meta data not buffered. Can't save it into buffer"))
      )
    {:dispatch [::next-segment-requested]}
    ))

(rf/reg-event-fx
  ::init-player
  (fn [db [_ player]]
    (tap> player)

    {:db db}))

(rf/reg-event-fx
  ::overflowed-buffer
  (fn []))

(rf/reg-fx
  ::append-buffer
  (fn [{:keys [audio-context source-buffer response]}]
    (try
      (go
        (let [resp (<p! response)
              media (<p! (-> resp :array-buffer))]
          (set! (.-appendWindowEnd source-buffer) (-> resp :content-range :end))
          (try
            (js/console.log media)
            
            (.decodeAudioData audio-context media)
            ;; (.decodeAudioData audio-context media
            ;;                   (fn [buffer]
            ;;                     (.appendBuffer source-buffer buffer)))
            
            
            (rf/dispatch-sync [::util.event/assoc-in [:player :last-range-request] (-> resp :content-range)])
            
            (catch js/DOMException e
              (.remove source-buffer )
              ;; TODO: Clean range buffer
              ;; TODO: may be lock other requests
              (js/console.log "Buffer is full" e)))
          ;; (tap> (<p! (-> resp :array-buffer)))
          ;; (tap> source-buffer)
          (subscribe source-buffer :update
                     #(rf/dispatch [::add-meta-data (-> resp :content-range)])
                     #js{:once true})))
      ;; (catch js/DOMException e
      ;; (js/console.error "Buffer is full"))
      (catch js/Error e
        (js/console.error "Something goes wrong on dispatching: " e)))))

(rf/reg-event-fx
  ::range-requested
  [(rf/inject-cofx ::player.injectors/stream-url)
   (rf/inject-cofx ::player.injectors/source-buffer)
   (rf/inject-cofx ::player.injectors/audio-context)]
  (fn [{{url :stream source-buffer :source-buffer audio-context :audio-context} :coeffects} [_ start]]
    (let [range-header (str "bytes=" start "-")]
      {::append-buffer
       {:audio-context audio-context
        :source-buffer source-buffer
        :response      (-> (js/fetch url (clj->js {:headers
                                                   {:range range-header}}))
                           (.then
                             (fn [resp]
                               {:content-range (resp->content-range resp)
                                :array-buffer
                                (.arrayBuffer resp)})))}})))

(rf/reg-event-fx
  ::next-segment-requested
  [(rf/inject-cofx ::player.injectors/last-range-request)]
  (fn [{{:keys [last-range-request]} :coeffects}]
    {:dispatch [::range-requested (inc (:end last-range-request))]}))

(rf/reg-event-fx
  ::range-seeked
  [(rf/inject-cofx ::player.injectors/meta-data-buffer)
   (rf/inject-cofx ::player.injectors/source-buffer)
   (rf/inject-cofx ::player.injectors/player)
   (rf/inject-cofx ::player.injectors/last-range-request)]
  (fn [{{:keys [player source-buffer meta-data-buffer last-range-request]} :coeffects}]
    (let [current-timestamp (.-currentTime player)]
      (if-let [?buffered (buffered-timestamp? source-buffer current-timestamp)]
        ;; NOTE: Range request when the requested buffer range is played for more then 80% percent.
        (let [meta-data      (lookup-in meta-data-buffer [:timestamp] ?buffered)
              start          (-> meta-data :timestamp :start)
              end            (-> meta-data :timestamp :end)
              played-percent (timestamp-percent current-timestamp start end)
              request-chunk  (-> meta-data :byte :end inc)]
          (when (>= played-percent 80)
            (.abort source-buffer)
            (set! (.-timestampOffset source-buffer) end)
            {:dispatch [::range-requested request-chunk]}))
        ;; NOTE: Seeked range is not buffered.
        (let [duration        (.-duration player)
              current-percent (duration-percent current-timestamp duration)
              request-chunk   (get-byte-chunk (:size last-range-request) current-percent)]
          (.abort source-buffer)
          (set! (.-timestampOffset source-buffer) current-timestamp)
          {:dispatch [::range-requested request-chunk]})))))

