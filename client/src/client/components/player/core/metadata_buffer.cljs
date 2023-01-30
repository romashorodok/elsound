(ns client.components.player.core.metadata-buffer
  (:refer-clojure :exclude [range byte]))

(defrecord Rangable [start end])

(defn- rangable>=start [start]
  (fn [rangable]
    (>= (:start rangable) start)))

(defn- rangable<=end [end]
  (fn [rangable]
    (<= (:end rangable) end)))

(defn- intersect
  "Return coll of rangable which intersect with given range"
  [coll rangable]
  (->> coll
       (filter (rangable>=start (:start rangable)))
       (filter (rangable<=end (:end rangable)))))

(defn- intersected? [intersection]
  (> (count intersection) 1))

(defrecord MetaData
    [^Rangable timestamp
     ^Rangable byte])

(defn ^MetaData meta-data [timestamp byte]
  (->MetaData 
    (->Rangable (:start timestamp)
                (:end timestamp))
    (->Rangable (:start byte)
                (:end byte))))

(defprotocol Buffered
  (lookup-in [this [& ks] {:keys [start end]}])
  (keep-sorted [this])
  (add [this meta-data]))


(defn- append [state meta-data]
  (let [idx (count @state)]
    (swap! state conj {idx meta-data})))

(defn- unify->rangable [prev rangable]
  (let [latest?  (> (:end rangable)
                    (:end prev))
        earlies? (< (:start rangable)
                    (:start prev))]
    (cond-> prev
      latest?  (assoc :end (:end rangable))
      earlies? (assoc :start (:start rangable)))))

(defn- unify-in
  "Return unified coll reduced by field name in given state from indexes"
  [coll state field idxes]
  (assoc coll field
         (reduce unify->rangable
                 (map #(get-in state [% field]) idxes))))

(deftype MetaDataBuffer [^Atom state]
  Buffered
  (lookup-in [this [& ks] {:keys [start end]}]
    (let [run?    (cljs.core/atom true)
          ?result (cljs.core/atom nil)
          state   @state]
      (dorun
        (for [idx    (cljs.core/range (count state))
              :while @run?
              :let   [meta-data (get state idx)
                      start? (:start (get-in meta-data ks))
                      end? (:end (get-in meta-data ks))]]
          (when (and (>= start start?)
                     (<= end end?))
            (reset! ?result (assoc meta-data :idx idx))
            (reset! run? false))))
      @?result))
  
  (keep-sorted [_]
    (swap! state #(->> (map-indexed
                         (fn [idx [_ item]]
                           {idx item})
                         (sort-by
                           (fn [[_ item]]
                             (get-in item [:timestamp :start])) %))
                       (into {}))))
  
  (add [this ^MetaData meta-data]
    (append state meta-data)
    (let [rangable-field [:timestamp]
          rangable       (get-in meta-data rangable-field)
          rangable-coll  (map #(assoc (get-in (val %) rangable-field) :idx (key %))
                              @state)
          intersection   (intersect rangable-coll rangable)]
      (if (intersected? intersection)
        (let [intersected-idxes (map :idx intersection)
              meta-data         (-> (apply dissoc meta-data [:timestamp :byte])
                                    (unify-in @state :timestamp intersected-idxes)
                                    (unify-in @state :byte intersected-idxes))]
          (swap! state #(->> (map-indexed
                               (fn [idx [_ item]]
                                 {idx item})
                               (sort-by
                                 (fn [[_ item]]
                                   (get-in item [:timestamp :start]))
                                 (-> (apply dissoc % intersected-idxes)
                                     (merge {nil meta-data}))))
                             (into {}))))
        (keep-sorted this))))

  IDeref
  (-deref [_] state))

