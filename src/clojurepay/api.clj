(ns clojurepay.api
  (:use net.cgrand.enlive-html
        ring.util.response
        sandbar.stateful-session
        [clojurepay.util :only [defsitehandler with-args swap-keys random-string]]
        [clojurepay.config :only [config]]
        [clojurepay.auth :only [owns-circle? is-member?]]
        clojurepay.model
        monger.operators)
  (:require [monger.collection :as mc]
            [clj-time.core :as time]
            monger.joda-time
            [cheshire.generate :refer [add-encoder encode-str]])
  (:import [org.bson.types ObjectId]))

(add-encoder org.bson.types.ObjectId encode-str)

(defsitehandler circles)
(defsitehandler circle)

(defmethod circles :get [method]
  ;; Return all circles of which the user is a member.
  (let [query {:users {:id (session-get :user)}}
        sort (array-map :created -1)
        circles (fetch-collection (->RecordCollection) (->Circle) query sort)]
    {:body circles :status 200}))

(defmethod circle :get [method id]
  ;; Return information on a given circle.
  (with-args [id]
    (let [circle (fetch (->Circle) id)]
      (if-not (is-member? circle)
        {:status 401}
        {:body circle :status 200}))))

(defmethod circle :post [method name]
  ;; Create a new circle.
  (with-args [name]
    (let [owner (fetch (->User) (session-get :user))]
      {:body (insert (->Circle) name owner) :status 200})))

(defmethod circle :delete [method id]
  (with-args [id]
    (let [circle-doc (mc/find-map-by-id "circle" (ObjectId. id))]
      (if-not (is-member? circle-doc)
        {:status 401}
        (do (mc/remove "circle" {:_id (ObjectId. id)})
            {:body {} :status 200})))))

(defn circle-reassign-owner [circle-id user-id]
  (let [circle-doc (mc/find-map-by-id "circle" (ObjectId. circle-id))
        user-is-owner? (fn [user] (= (:id user) (get-in circle-doc [:owner :id])))
        new-owner-id (->> (:users circle-doc)
                          (remove user-is-owner?)
                          (first)
                          (:id))
        new-owner-doc (mc/find-map-by-id "user" new-owner-id)]
    (if-not (owns-circle? circle-doc)
      {:status 401}
      (do (mc/update-by-id "circle"
                           (ObjectId. circle-id)
                           {$set {:owner {:id new-owner-id
                                          :name (:name new-owner-doc)}}})
          {:body {} :status 200}))))

(defn circle-remove-member [circle-id user-id]
  (let [circle-doc (mc/find-map-by-id "circle" (ObjectId. circle-id))]
    (if-not (or (owns-circle? circle-doc) (= user-id (session-get :user)))
      {:status 401}
      (do (mc/update-by-id "circle"
                           (ObjectId. circle-id)
                           {$pull {:users {:id user-id}}})
          {:body {} :status 200}))))

(defn circle-join [circle-id invite-code]
  (let [circle-doc (mc/find-map-by-id "circle" (ObjectId. circle-id))
        is-already-member (some #{(session-get :user)} (map :id (:users circle-doc)))
        valid (and (not (nil? circle-doc))
                   (not is-already-member)
                   (= invite-code (:invite_code circle-doc)))]
    (if-not valid
      {:status 400}
      (do (mc/update-by-id "circle"
                           (ObjectId. circle-id)
                           {$push {:users {:id (session-get :user)}}})
          {:status 200}))))
