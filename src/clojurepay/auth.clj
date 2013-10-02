(ns clojurepay.auth
  (:use [clojurewerkz.scrypt.core :as sc])
  (:require [monger.collection :as mc]))

(def lcase clojure.string/lower-case)

(defn email-exists? [email]
  (let [l-email (clojure.string/lower-case email)]
    (pos? (mc/count "user" {:_id l-email}))))

(defn encrypt-password [password] (sc/encrypt password 16384 8 1))

(defn password-is-correct? [email password]
  (let [user-rec (mc/find-map-by-id "user" (lcase email))]
    (if (empty? user-rec)
      false
      (sc/verify password (:password user-rec)))))

(defn logged-in? [session]
  (contains? session :logged-in))

(defn login-user
  "Add successful user auth info to current session."
  [session]
  (assoc session :logged-in "You betcha"))

(defn save-new-user [name email password]
  (mc/insert "user" {:_id (lcase email)
                     :name name
                     :proper-email email
                     :password (encrypt-password password)}))
