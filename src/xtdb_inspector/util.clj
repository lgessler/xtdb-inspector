(ns xtdb-inspector.util
  (:import (java.net URLEncoder))
  (:require [clojure.string :as str]))

(defn enc
  "URLEncode a thing"
  [x]
  (-> x
      URLEncoder/encode
      (str/replace "+" "%20")))

(def root-path
  (or (System/getenv "XTDB_INSPECTOR_ROOT_PATH") "/i"))

(defn ->route
  [s]
  (str root-path s))
