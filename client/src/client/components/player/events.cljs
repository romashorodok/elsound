(ns client.components.player.events
  (:refer-clojure :exclude [byte])
  (:require [cljs.core.async :refer [go take]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [re-frame.core :as rf]
            [client.components.player.injectors :as player.injectors]
            [client.util.subscription :refer [subscribe]]
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
        (js/console.error "Meta data not buffered. Can't save it into buffer")))
    {:db (assoc-in db [:player :last-range-request] content-range)}))

(rf/reg-fx
  ::append-buffer
  (fn [{:keys [source-buffer response]}]
    (try
      (go
        (let [resp (<p! response)]
          (set! (.-appendWindowEnd source-buffer) (-> resp :content-range :end))
          (.appendBuffer source-buffer (<p! (-> resp :array-buffer)))
          (subscribe source-buffer :updateend
                     #(rf/dispatch [::add-meta-data (-> resp :content-range)])
                     #js{:once true})))
      (catch js/Error e
        (js/console.error "Something goes wrong on dispatching: " e)))))

(rf/reg-event-fx
  ::range-requested
  [(rf/inject-cofx ::player.injectors/stream-url)
   (rf/inject-cofx ::player.injectors/source-buffer)]
  (fn [{{url :stream source-buffer :source-buffer} :coeffects} [_ start]]
    (let [range-header (str "bytes=" start "-")]
      {::append-buffer
       {:source-buffer source-buffer
        :response      (-> (js/fetch url (clj->js {:headers
                                                   {:range range-header}}))
                           (.then
                             (fn [resp]
                               {:content-range (resp->content-range resp)
                                :array-buffer  (.arrayBuffer resp)})))}})))

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
              request-chunk  (-> meta-data :byte :end)]
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

