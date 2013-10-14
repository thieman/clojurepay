(ns clojurepay.venmo
  (:use [clojurepay.config :only [config]]
        clojurepay.model)
  (:require [clj-http.client :as client]
            [cheshire.core :as cheshire]
            [clj-time.core :as time])
  (:import [org.bson.types ObjectId]))

(declare charge-member! queue-charges!)

(defn- consume-charge! [charge]
  (charge-member! charge)
  (complete charge))

(defn- charge-consumer []
  (while true
    (try
      (when-let [charge (main-stage-pop (->Charge))]
        (consume-charge! charge))
      (catch ClassCastException e ()) ; Mongo var not bound yet
      (catch Exception e (println e)))
    (Thread/sleep 10)))

(defn- charge-retry-consumer []
  (while true
    (try
      (when-let [charge (retry-pop (->Charge))]
        (consume-charge! charge))
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
  (let [make-charge (fn [target] (create (->Charge) {:token token
                                                     :amount amount
                                                     :memo memo
                                                     :email (:id target)}))]
    (batch-insert (->ChargeCollection (map make-charge targets)))))

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
