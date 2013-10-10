(ns clojurepay.venmo
  (:use [clojurepay.config :only [config]]
        [monger.operators])
  (:require [clj-http.client :as client]
            [monger.collection :as mc]
            [cheshire.core :as cheshire]
            [clojure.core.async :as async])
  (:import [org.bson.types ObjectId]))

(declare charge-member! queue-charges! do-work!)

(def charge-channel (async/chan (async/dropping-buffer 100)))

(defn consume-charge [queue-objectid]
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
        (async/>!! charge-channel queue-objectid)
        (catch Exception e
          (println e))))))

(defn consumer-loop []
  (when-let [queue-objectid (async/<!! charge-channel)]
    (consume-charge queue-objectid)
    (recur)))

(dotimes [n 5] (doto
                 (Thread. consumer-loop)
                 (.setDaemon true)
                 (.start)))

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
    (async/>!! charge-channel queue-objectid)))

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

(defn- charge-member! [{:keys [token email amount memo]}]
  (let [endpoint (clojure.string/join "/" [(:venmo-api-url config) "payments"])
        result (client/post endpoint {:form-params {:access_token token
                                                    :email email
                                                    :amount (* -1 amount)
                                                    :note (or memo "Charge via ClojurePay")}})]
    (assert (= 200 (:status result)))))
