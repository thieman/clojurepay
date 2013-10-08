(ns clojurepay.api
  (:use net.cgrand.enlive-html
        ring.util.response
        sandbar.stateful-session
        [clojurepay.util :only [defsitehandler with-args swap-keys]]
        [clojurepay.config :only [config]]
        [clojurepay.auth :only [owns-circle?]]
        monger.operators)
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
    (mc/remove "circle" {:_id (ObjectId. id)})
    {:body {} :status 200}))

(defn circle-reassign-owner [circle-id user-id]
  (let [circle-doc (mc/find-map-by-id "circle" (ObjectId. circle-id))
        user-is-owner? (fn [user] (= (:id user) (get-in circle-doc [:owner :id])))
        new-owner-id (->> (:users circle-doc)
                          (filter user-is-owner?)
                          (first)
                          (:id))
        new-owner-doc (mc/find-map-by-id "user" new-owner-id)]
    (if-not (owns-circle?)
      {:status 401}
      (do (mc/update-by-id "circle"
                           circle-id
                           {$set {:owner {:id new-owner-id
                                          :name (:name new-owner-doc)}}})
          {:body {} :status 200}))))

(defn circle-remove-member [circle-id user-id]
  (let [circle-doc (mc/find-map-by-id "circle" (ObjectId. circle-id))]
    (if-not (or (owns-circle?) (= user-id (session-get :user)))
      {:status 401}
      (do (mc/update-by-id "circle"
                           circle-id
                           {$pull {:users {:id (session-get :user)}}})
          {:body {} :status 200}))))
