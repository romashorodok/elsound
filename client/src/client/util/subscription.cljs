(ns client.util.subscription
  (:require-macros [client.util.subscription]))

(defn subscribe [element target f options]
  (if-let [event-target? (instance? js/EventTarget element)]
    (.addEventListener element (key->js target) f options)
    (js/console.error "Subscribe atom should contain js/EventTarget element")))

(defn unsubscribe [element target f]
  (if-let [evet-target? (instance? js/EventTarget element)]
    (.removeEventListener element (key->js target) f)
    (js/console.warn "Element already unsubscribed. Or something goes wrong")))
