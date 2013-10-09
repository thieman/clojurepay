(ns clojurepay.venmo
  (:use [clojurepay.config :only [config]]
        [monger.operators])
  (:require [clj-http.client :as client]
            [monger.collection :as mc]
            [cheshire.core :as cheshire])
  (:import [org.bson.types ObjectId]))

(def queue-worker (agent nil))

(declare charge-member! queue-charges! do-work!)

(defn charge-circle!
  "Split a charge amongst all members of a circle, excluding the
  originating member. Queues work, then tries to perform it using
  two-stage pop.  WARNING: Private API call! Performs no validation
  once called."
  [circle-id user-id amount memo]
  (let [circle-doc (mc/find-map-by-id "circle" (ObjectId. circle-id))
        user-doc (mc/find-map-by-id "user" user-id)
        user-is-owner? (fn [user] (= (:id user) (get-in circle-doc [:owner :id])))
        charger-token (:token user-doc)
        payment-amount (-> (/ amount (count (:users circle-doc)))
                           (* 100.0)
                           (Math/floor)
                           (/ 100.0))
        queue-objectid (queue-charges! charger-token
                                       (remove user-is-owner? (:users circle-doc))
                                       payment-amount
                                       memo)]
    (send queue-worker do-work! queue-objectid)))

(defn do-work!
  "Fortune favors the bold."
  [queue-objectid _]
  (let [queue-doc (mc/find-and-modify "charge_queue"
                                      {:_id queue-objectid}
                                      {$pop {:charges -1}})
        work-doc (first (:charges queue-doc))
        new-work-id (ObjectId.)]
    (if (nil? work-doc)
      (mc/remove-by-id "charge_queue" queue-objectid)
      (try
        (mc/insert "charge_retry" (merge {:_id new-work-id} work-doc))
        (charge-member! work-doc)
        (mc/remove-by-id "charge_retry" new-work-id)
        (send queue-worker do-work! queue-objectid)
        (catch Exception e
          (println e))))))

(defn queue-charges!
  "Create a new doc in Mongo to queue a bunch of payments. Returns
  ObjectId of the created doc."
  [token targets amount memo]
  (let [new-id (ObjectId.)
        charge (fn [target] {:token token
                             :amount amount
                             :memo memo
                             :email (:id target)})]
    (mc/insert "charge_queue" {:_id new-id
                               :charges (map charge targets)})
    new-id))

(defn- charge-member! [{:keys [token email amount memo]} work-doc]
  (let [endpoint (clojure.string/join "/" [(:venmo-api-url config) "payments"])
        result (client/post endpoint {:form-params {:access_token token
                                                    :email email
                                                    :amount amount
                                                    :note (or memo "Charge via ClojurePay")}})]
    (assert (= 200 (:status result)))))
