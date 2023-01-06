(ns platform.system.util.reader
  (:require [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file Files]
           [platform.system.header.range Range]))

(defprotocol Chunkable
  (chunk-length [this])
  (chunk-start [this])
  (chunk-end [this]))

(defprotocol ByteReadable
  (read->bytes [this])
  (unit [this]))

(defprotocol MetaFile
  (file-size [this]))

(defn- get-file-size [^File file]
  (let [path (.toPath file)]
    (Files/size path)))

(defn- overflow? [chunk file-size]
  (>= chunk file-size))

(defn- normalize-overflow-chunk [chunk file-size]
  (let [overflow-diff (- file-size chunk)]
    (+ chunk overflow-diff)))

(defn- default-chuk-size [file-size]
  (quot file-size 100))

(defn range-reader [file-path ^Range {:keys [range-start range-end unit]}]
  (let [file       (io/file file-path)
        file-size  (get-file-size file)
        chunk-size (default-chuk-size file-size)]
    (reify
      Chunkable
      (chunk-start [_] range-start)
      ;; TODO: case when range-end passed
      (chunk-end [this]
        (let [end (+ (chunk-start this) chunk-size)]
          (if (overflow? end file-size)
            (normalize-overflow-chunk end file-size)
            end)))
      (chunk-length [this] (- (chunk-end this) range-start))

      MetaFile
      (file-size [_] file-size)

      ByteReadable
      (unit [_] unit)
      (read->bytes [this]
        (with-open [reader (io/input-stream file)]
          (.skip reader (chunk-start this))
          (.readNBytes reader (+ 1 (- (chunk-end this)
                                      (chunk-start this)))))))))
