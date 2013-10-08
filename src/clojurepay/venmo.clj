(ns clojurepay.venmo
  (:use [clojurepay.config :only [config]])
  (:require [clj-http.client :as client]
            [monger.collection :as mc]
            [cheshire.core :as cheshire])
  (:import [org.bson.types ObjectId]))

(declare charge-member)

(defn charge-circle
  "Split a charge amongst all members of a circle, excluding the
  originating member.

  WARNING: Private API call! Performs no validation once called."
  [circle-id user-id amount memo]
  (let [circle-doc (mc/find-map-by-id "circle" (ObjectId. circle-id))
        user-doc (mc/find-map-by-id "user" user-id)
        user-is-owner? (fn [user] (= (:id user) (get-in circle-doc [:owner :id])))
        charger-token (:token user-doc)
        payment-amount (-> (/ amount (count (:users circle-doc)))
                           (* 100.0)
                           (Math/floor)
                           (/ 100.0))]
    (doseq [member (remove user-is-owner? (:users circle-doc))]
      (let [chargee-doc (mc/find-map-by-id "user" (:id member))]
        (charge-member charger-token (:proper-email chargee-doc) payment-amount memo)))))

(defn charge-member [token email amount memo]
  (let [endpoint (clojure.string/join "/" [(:venmo-api-url config) "payments"])
        result (client/post endpoint {:form-params {:access_token token
                                                    :email email
                                                    :amount amount
                                                    :note (or memo "Charge via ClojurePay")}})]
    (println result)))
