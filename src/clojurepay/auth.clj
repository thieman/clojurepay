(ns clojurepay.auth
  (:use [clojurewerkz.scrypt.core :as sc]
        ring.util.response
        [clojurepay.config :only [config]]
        clojurepay.model
        sandbar.stateful-session))

(defn email-exists? [email]
  (let [user (fetch (->User) (clojure.string/lower-case email))]
    (not (nil? user))))

(defn encrypt-password [password] (sc/encrypt password 16384 8 1))

(defn password-is-correct? [email password]
  (let [user (fetch (->User) (clojure.string/lower-case email))]
    (if (nil? user)
      false
      (sc/verify password (:password user)))))

(defn logged-in? []
  (let [l-email (session-get :user)]
    (if (nil? l-email)
      false
      (sc/verify (str l-email (:app_secret config)) (session-get :token)))))

(defn login-user
  "Add successful user auth info to current session."
  [email]
  (let [l-email (clojure.string/lower-case email)]
    (session-put! :user l-email)
    (session-put! :token (sc/encrypt (str l-email (:app_secret config)) 16384 8 1))))

(defn save-new-user [name email password]
  (if (email-exists? email)
    (throw (Exception. "This email is taken, please choose another."))
    (insert (->User) {:email email
                      :name name
                      :password (encrypt-password password)})))

(defn is-member? [circle-doc]
  (boolean (some #(= (:id %) (session-get :user)) (:users circle-doc))))

(defn owns-circle? [circle-doc]
  (= (session-get :user) (get-in circle-doc [:owner :id])))

(defn self? [user-doc]
  (= (session-get :user) (:_id user-doc)))
