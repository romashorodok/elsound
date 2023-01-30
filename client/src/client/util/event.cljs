(ns client.util.event
  (:require [re-frame.core :as rf]))

(rf/reg-sub
  ::get-in
  (fn [db [_ db-path default-val]]
    (get-in db db-path default-val)))

(rf/reg-event-db
  ::assoc-in
  (fn [db [_ db-path val]]
    (assoc-in db db-path val)))

(rf/reg-event-db
  ::dissoc-in
  (fn [db [_ db-path ks]]
    (assert (seqable? ks))
    (update-in db db-path #(apply dissoc % ks))))

(rf/reg-event-db
  ::update-in
  (fn [db [_ db-path f & args]]
    (apply update-in db db-path f args)))

