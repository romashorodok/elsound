(ns client.util.subscription)

;; (:ns &env) ;; :ns only exists in CLJS

(defmacro subscribe-atom
  ([iref target f]
   `(subscribe-atom ~iref ~target ~f ~{}))
  ([iref target f opts]
   `(add-watch ~iref (str (random-uuid) ~(keyword target))
               (fn [_# _#  old# new#]
                 (if new#
                   (do
                     (subscribe new# ~target ~f ~opts)
                     (when old#
                       (unsubscribe old# (str ~target) ~f)))
                   (unsubscribe old# (str ~target) ~f))))))
