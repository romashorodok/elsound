(ns platform.system.header.protocol)

(defprotocol Header
  (->header [this]
    "Transform protocol object to HTTP header string"))
