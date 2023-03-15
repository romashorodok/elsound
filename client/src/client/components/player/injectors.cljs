(ns client.components.player.injectors
  (:require [re-frame.core :as rf]))

(rf/reg-cofx
  ::source-buffer
  (fn [context]
    (let [source-buffer (get-in context [:db :player :source-buffer])]
      (assoc-in context [:coeffects :source-buffer] source-buffer))))

(rf/reg-cofx
  ::player
  (fn [context]
    (let [player (get-in context [:db :player :ref])]
      (assoc-in context [:coeffects :player] player))))

(rf/reg-cofx
  ::audio-context
  (fn [context]
    (let [player (get-in context [:db :player :audio-context])]
      (assoc-in context [:coeffects :audio-context] player))))

(rf/reg-cofx
  ::meta-data-buffer
  (fn [context]
    (let [meta-data-buffer (get-in context [:db :player :meta-data-buffer])]
      (assoc-in context [:coeffects :meta-data-buffer] meta-data-buffer))))

(rf/reg-cofx
  ::last-range-request
  (fn [context]
    (let [last-range-request (get-in context [:db :player :last-range-request])]
      (assoc-in context [:coeffects :last-range-request] last-range-request))))

(rf/reg-cofx
  ::stream-url
  (fn [context]
    (let [stream (get-in context [:db :player :stream])]
      (assoc-in context [:coeffects :stream] stream))))
