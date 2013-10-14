(ns clojurepay.model
  (:use [clojurepay.util :only [swap-keys random-string assert-args]]
        monger.operators)
  (:require [monger.collection :as mc]
            [monger.query :as mq]
            [clj-time.core :as time])
  (:import [org.bson.types ObjectId]))

(defprotocol StaticParams
  (opts [this key]))

(defprotocol PersistableDocumentCollection
  (fetch-collection [this record-type query sort])
  (batch-insert [this members]))

(defprotocol RobustDocument
  (create [this args-map])
  (migrate [this])
  (main-stage-pop [this])
  (main-stage-retry-pop [this])
  (retry-stage-retry-pop [this])
  (retry-pop [this])
  (complete [this]))

(defprotocol Document
  (fetch [this id])
  (delete [this])
  (parse [this doc])
  (insert [this args-map])
  (update [this query]))

(defrecord RecordCollection []

  PersistableDocumentCollection
  (fetch-collection [this base-record query sort]
    (let [coll (opts base-record :coll)
          docs (mq/with-collection coll (mq/find query) (mq/sort sort))]
      (map #(parse base-record %) docs)))

  (batch-insert [this members]
    (mc/insert-batch (opts (first members) :coll) members)))

(defrecord Charge []

  StaticParams
  (opts [this key]
    (let [config {:coll "charge"
                  :retry_coll "charge_retry"
                  :retry_after (time/minutes 10)}]
      (get config key)))

  RobustDocument
  (create [this {:keys [token amount memo email]}]
    (merge (->Charge) {:_id (ObjectId.)
                       :token token
                       :amount amount
                       :memo memo
                       :email email
                       :created (time/now)}))

  (migrate [this]
    (mc/update-by-id (opts this :retry_coll)
                     (:_id this)
                     (assoc this :last_attempt (time/now))
                     :upsert true)
    (mc/remove-by-id (opts this :coll) (:_id this))
    this)

  (main-stage-pop [this]
    (when-let [fetched-doc (mc/find-and-modify (opts this :coll)
                                               {:locked {$exists false}}
                                               {$set {:locked (time/now)}}
                                               :sort {:created 1})]
      (migrate (merge (->Charge) fetched-doc))))

  (main-stage-retry-pop [this]
    (let [query {:locked {$lte (time/minus (time/now)
                                           (opts this :retry_after))}}
          fetched-doc (mc/find-and-modify (opts this :coll)
                                          query
                                          {$set {:locked (time/now)}}
                                          :sort {:locked 1})]
      (when fetched-doc
        (merge this fetched-doc))))

  (retry-stage-retry-pop [this]
    (let [query {:last_attempt {$lte (time/minus (time/now)
                                                 (opts this :retry_after))}}
          fetched-doc (mc/find-and-modify (opts this :retry_coll)
                                          query
                                          {$set {:last_attempt (time/now)}}
                                          :sort {:last_attempt 1})]
      (when fetched-doc
        (merge this fetched-doc))))

  (retry-pop [this]
    (if-let [charge (main-stage-retry-pop this)]
      (migrate charge)
      (when-let [charge (retry-stage-retry-pop this)]
        (migrate charge))))

  (complete [this]
    (mc/remove-by-id (opts this :retry_coll) (:_id this))
    this))

(defrecord User []

  StaticParams
  (opts [this key]
    (let [config {:coll "user"}]
      (get config key)))

  Document
  (fetch [this _id]
    (let [user-doc (mc/find-map-by-id (opts this :coll) _id)
          new-user (assoc (->User) :_id _id)]
      (when user-doc
        (merge new-user user-doc))))

  (delete [this]
    (let [id (:_id this)]
      (when id (mc/remove (opts this :coll) {:_id (ObjectId. (str id))}))))

  (parse [this doc] (merge (->User) doc))

  (insert [this {:keys [email name password]}]
    (assert-args [email name password])
    (mc/insert (opts this :coll)
               {:_id (clojure.string/lower-case email)
                :active false
                :name name
                :proper-email email
                :password password}))

  (update [this query]
    (mc/update-by-id (opts this :coll)
                     (:_id this)
                     query)
    (fetch (->User) (:_id this))))

(defrecord Circle []

  StaticParams
  (opts [this key]
    (let [config {:coll "circle"}]
      (get config key)))

  Document
  (fetch [this _id]
    (let [circle-doc (mc/find-map-by-id (opts this :coll) (ObjectId. (str _id)))
          new-circle (assoc (->Circle) :_id _id)]
      (when circle-doc
        (merge new-circle circle-doc))))

  (delete [this]
    (let [id (:_id this)]
      (when id (mc/remove (opts this :coll) {:_id (ObjectId. (str id))}))))

  (parse [this doc] (merge (->Circle) doc))

  (insert [this {:keys [name owner-doc]}]
    (assert-args [name owner-doc])
    (let [circle-id (ObjectId. )
          invite-code (random-string 20)
          now (time/now)]
      (mc/insert (opts this :coll)
                 {:_id circle-id
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

  (update [this query]
    (mc/update-by-id (opts this :coll)
                     (:_id this)
                     query)
    (fetch (->Circle) (:_id this))))
