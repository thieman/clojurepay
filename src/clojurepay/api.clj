(ns clojurepay.api
  (:use net.cgrand.enlive-html
        ring.util.response
        sandbar.stateful-session
        [clojurepay.util :only [defsitehandler with-args swap-keys]]
        [clojurepay.config :only [config]])
  (:require [monger.collection :as mc]
            [monger.query :as mq]
            [clj-time.core :as time]
            monger.joda-time
            [cheshire.generate :refer [add-encoder encode-str]])
  (:import [org.bson.types ObjectId]))

(add-encoder org.bson.types.ObjectId encode-str)

(defsitehandler circles)
(defsitehandler circle)

(defmethod circles [:get] [method]
  ;; Return all circles of which the user is a member.
  (let [user-circles (mq/with-collection "circle"
                       (mq/find {:users {:id (session-get :user)}})
                       (mq/sort (array-map :created -1)))]
    {:body user-circles
     :status 200}))

(defmethod circle [:get] [method params id]
  ;; Return information on a given circle.
  (with-args [id]
    {:body (mc/find-map-by-id "circle" (ObjectId. id))
     :status 200}))

(defmethod circle [:post] [method name]
  ;; Create a new circle.
  (with-args [name]
    (let [owner-doc (mc/find-map-by-id "user" (session-get :user))
          new-circle {:_id (ObjectId.)
                      :name name
                      :owner (-> owner-doc
                                 (select-keys [:_id :name])
                                 (swap-keys :_id :id))
                      :users [{:id (session-get :user)}]
                      :created (time/now)
                      :updated (time/now)}]
      {:body (mc/insert-and-return "circle" new-circle)
       :status 200})))

(defmethod circle [:delete] [method params id]
  (with-args [id]
    (mc/remove "circle" {:_id (ObjectId. id)})))
