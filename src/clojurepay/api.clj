(ns clojurepay.api
  (:use [net.cgrand.enlive-html]
        ring.util.response
        sandbar.stateful-session
        [clojurepay.config :only [config]])
  (:require [monger.collection :as mc]
            [monger.query :as mq]
            [clj-time.core :as time]
            monger.joda-time
            [cheshire.generate :refer [add-encoder encode-str]])
  (:import [org.bson.types ObjectId]))

(add-encoder org.bson.types.ObjectId encode-str)

(defn dispatch-on-method [method & args] [method])

(defmacro def-default-method-handler [method]
  `(defmethod ~method :default [& args#] {:status 405}))

(defn def-default-method-handlers [& methods]
  (doseq [method methods]
    (def-default-method-handler method)))

(defmulti circles dispatch-on-method)

(defmethod circles [:get] [method]
  ;; Return all circles of which the user is a member.
  (let [user-circles (mq/with-collection "circle"
                       (mq/find {:users {:id (session-get :user)}})
                       (mq/sort (array-map :created -1)))]
    user-circles))

(defmulti circle dispatch-on-method)

(defmethod circle [:get] [method params id]
  ;; Return information on a given circle.
  (mc/find-map-by-id "circle" (ObjectId. id)))

(defmethod circle [:post] [method name]
  ;; Create a new circle.
  (let [new-circle {:_id (ObjectId.)
                    :name name
                    :users [{:id (session-get :user)}]
                    :created (time/now)}]
    (mc/insert-and-return "circle" new-circle)))

(def-default-method-handlers circles circle)
