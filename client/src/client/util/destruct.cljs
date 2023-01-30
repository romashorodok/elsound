(ns client.util.destruct
  (:refer-clojure :exclude [get])
  (:require [goog.object :refer [get]]))

(defn destruct [o]
  (reify
    ILookup
    (-lookup [_ k]
      (get o (name k)))
    (-lookup [_ k not-found]
      (get o (name k) not-found))))
