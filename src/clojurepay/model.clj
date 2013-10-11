(ns clojurepay.model
  (:use [clojurepay.util :only [swap-keys random-string]])
  (:require [monger.collection :as mc]
            [monger.query :as mq]
            [clj-time.core :as time])
  (:import [org.bson.types ObjectId]))

(defprotocol PersistableRecordCollection
  (fetch-collection [_ record-type query sort]))

(defprotocol Document
  (opts [_ k])
  (fetch [_ id])
  (parse [_ doc]))

(defprotocol PersistableCircle
  (insert [_ name owner-doc])
  (update [_]))

(defrecord User []
  Document
  (opts [_ k]
    (let [config {:coll "user"}]
      (get config k)))

  (fetch [_ _id]
    (let [user-doc (mc/find-map-by-id "user" (ObjectId. (str _id)))
          new-user (assoc (->User) {:_id _id})]
      (merge new-user user-doc)))

  (parse [_ doc] (merge (->User) doc)))

(defrecord Circle []
  Document
  (opts [_ k]
    (let [config {:coll "circle"}]
      (get config k)))

  (fetch [_ _id]
    (let [circle-doc (mc/find-map-by-id "circle" (ObjectId. (str _id)))
          new-circle (assoc (->Circle) {:_id _id})]
      (merge new-circle circle-doc)))

  (parse [_ doc] (merge (->Circle) doc))

  PersistableCircle
  (insert [_ name owner-doc]
    (let [circle-id (ObjectId. )
          invite-code (random-string 20)
          now (time/now)]
      (mc/insert "circle" {:_id circle-id
                           :name name
                           :owner (-> owner-doc
                                      (select-keys [:_id :name])
                                      (swap-keys :_id :id))
                           :users [(-> owner-doc
                                       (select-keys [:_id])
                                       (swap-keys :_id :id))]
                           :invite_code invite-code
                           :created now
                           :updated now})))

  (update [_] (println "update")))

(defrecord RecordCollection []
  PersistableRecordCollection
  (fetch-collection [_ base-record query sort]
    (let [coll (opts base-record :coll)
          docs (mq/with-collection coll (mq/find query) (mq/sort sort))]
      (map #(parse base-record %) docs))))
