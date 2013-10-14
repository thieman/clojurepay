(ns clojurepay.venmo
  (:use [clojurepay.config :only [config]]
        clojurepay.model
        [monger.operators])
  (:require [clj-http.client :as client]
            [monger.collection :as mc]
            [cheshire.core :as cheshire]
            [clj-time.core :as time])
  (:import [org.bson.types ObjectId]))

(def retry-charge-after (time/minutes 10))

(declare charge-member! queue-charges!)

(defn- consume-charge! [charge-doc]
  (charge-member! charge-doc)
  (mc/remove-by-id "charge_retry" (:_id charge-doc)))

(defn- move-doc-to-retry [coll retry_coll move-doc]
  (mc/update-by-id retry_coll
                   (:_id move-doc)
                   (assoc move-doc :last_attempt (time/now))
                   :upsert true)
  (mc/remove-by-id coll (:_id move-doc)))

(defn- mongo-pop-with-retry! [coll retry_coll]
  (when-let [pop-doc (mc/find-and-modify coll
                                         {:locked {$exists false}}
                                         {$set {:locked (time/now)}}
                                         :sort {:created 1})]
    (move-doc-to-retry "charge" "charge_retry" pop-doc)
    pop-doc))

(defn- get-failed-charge-doc! []
  (mc/find-and-modify "charge"
                      {:locked {$lte (time/minus
                                      (time/now)
                                      retry-charge-after)}}
                      {$set {:locked (time/now)}}
                      :sort {:locked 1}))

(defn- get-timed-out-retry-doc! []
  (mc/find-and-modify "charge_retry"
                      {:last_attempt {$lte (time/minus
                                            (time/now)
                                            retry-charge-after)}}
                      {$set {:last_attempt (time/now)}}
                      :sort {:last_attempt 1}))

(defn- charge-consumer []
  (while true
    (try
      (when-let [charge-doc (mongo-pop-with-retry! "charge" "charge_retry")]
        (consume-charge! charge-doc))
      (catch ClassCastException e ()) ; Mongo var not bound yet
      (catch Exception e (println e)))
    (Thread/sleep 10)))

(defn- charge-retry-consumer []
  (while true
    (try
      (if-let [charge-doc (get-failed-charge-doc!)]
        (do (move-doc-to-retry "charge" "charge_retry" charge-doc)
            (consume-charge! charge-doc))
        (when-let [retry-doc (get-timed-out-retry-doc!)]
          (consume-charge! retry-doc)))
      (catch ClassCastException e ()) ; Mongo var not bound yet
      (catch Exception e (println e)))
    (Thread/sleep 10)))

(defn charge-circle!
  "Split a charge amongst all members of a circle, excluding the
  originating member. Queues work, then tries to perform it using
  two-stage pop.  WARNING: Private API call! Performs no validation
  once called."
  [circle-id user-id amount memo]
  (let [circle (fetch (->Circle) circle-id)
        user (fetch (->User) user-id)
        user-is-owner? (fn [user] (= (:id user) (get-in circle [:owner :id])))
        charger-token (:token user)
        payment-amount (-> (/ amount (count (:users circle)))
                           (* 100.0)
                           (Math/floor)
                           (/ 100.0))]
    (queue-charges! charger-token
                    (remove user-is-owner? (:users circle))
                    payment-amount
                    memo)))

(defn- queue-charges!
  "Create new docs in Mongo to queue a circle charge worth of
  payments."
  [token targets amount memo]
  (let [make-doc (fn [target] {:token token
                               :amount amount
                               :memo memo
                               :created (time/now)
                               :email (:id target)})]
    (mc/insert-batch "charge" (map make-doc targets))))

(defn- charge-member! [{:keys [token email amount memo]}]
  (let [endpoint (clojure.string/join "/" [(:venmo-api-url config) "payments"])
        result (client/post endpoint {:form-params {:access_token token
                                                    :email email
                                                    :amount (* -1 amount)
                                                    :note (or memo "Charge via ClojurePay")}})]
    (assert (= 200 (:status result)))))

(dotimes [n 5] (doto
                 (Thread. charge-consumer)
                 (.setDaemon true)
                 (.start)))

(dotimes [n 5] (doto
                 (Thread. charge-retry-consumer)
                 (.setDaemon true)
                 (.start)))
