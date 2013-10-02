(ns clojurepay.auth
  (:use [clojurewerkz.scrypt.core :as sc])
  (:require [monger.collection :as mc])
  (:import [org.bson.types ObjectId]))

(defn email-exists? [email]
  (let [l-email (clojure.string/lower-case email)]
    (pos? (mc/count "user" {:l_email l-email}))))

(defn encrypt-password [password]
  (sc/encrypt password 16384 8 1))

(defn password-is-correct? [email password] false)

(defn logged-in? [session] false)

(defn login-user [session] true)

(defn save-new-user [name email password]
  (let [l-email (clojure.string/lower-case email)]
    (mc/insert "user" {:_id (ObjectId.)
                       :name name
                       :email email
                       :l_email l-email
                       :password (encrypt-password password)})))
