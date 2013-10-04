(ns clojurepay.views
  (:use [net.cgrand.enlive-html]
        sandbar.stateful-session
        [clojurepay.config :only [config]]
        clojurepay.helpers
        clojurepay.auth)
  (:require [monger.collection :as mc]
            [clojurepay.api :as api]))

(defsnippet navbar-link "public/templates/navbar-link.html" [:li] [id href text]
  [:li] (set-attr :id id)
  [:a] (do-> (set-attr :href href)
             (content text)))

(defsnippet navbar "public/templates/navbar.html" [:.navbar] []
  [:#navbar-right] (if (logged-in?)
                     (append (navbar-link "logout" "/logout" "Log Out"))
                     (append (navbar-link "login" "/login" "Log In"))))

(defsnippet footer "public/templates/footer.html" [root] [] identity)

(defsnippet alert "public/templates/alert.html" [:.alert] [msg class]
  [:.alert] (do-> (add-class (str "alert-" (if (nil? msg) "hidden" class)))
                  (content msg)))

(deftemplate base-template "public/templates/base.html" [body-content]
  [:#body-content] (content body-content)
  [:#navbar] (substitute (navbar))
  [:#footer] (substitute (footer)))

(defsnippet signup-form "public/templates/signup-form.html" [:form] [form-action msg]
  [:form] (do-> (prepend (alert msg "warning"))
                (set-attr :action form-action)))

(defsnippet login-form "public/templates/login-form.html" [:form] [form-action msg]
  [:form] (do-> (prepend (alert msg "warning"))
                (set-attr :action form-action)))

(defsnippet circles "public/templates/circles.html" [root] [] identity)

(defsnippet circle-list-element "public/templates/circle-list-element.html" [:.circle] [] identity)

(defn index-redirect []
  (redirect-to (if (logged-in?) "/circles" "/signup")))

(defn signup-view
  ([] (signup-view nil))
  ([msg] (base-template (signup-form "do-signup" msg))))

(defn login-view
  ([] (login-view nil))
  ([msg] (base-template (login-form "do-login" msg))))

(defn authed-view []
  (auth-site (base-template "Yay, logged in!")))

(defn do-signup-view [params]
  (if (email-exists? (:email params))
    (signup-view "A user with this email already exists.")
    (try
      (save-new-user (:name params) (:email params) (:password params))
      (login-user (:email params))
      (redirect-to "/")
      (catch Exception e
        (signup-view "There was an error creating your account. Please try again.")))))

(defn do-login-view [params]
  (if (password-is-correct? (:email params) (:password params))
    (do
      (login-user (:email params))
      (redirect-to "/"))
    (login-view "Incorrect email or password.")))

(defn logout-view []
  (destroy-session!)
  (redirect-to "/"))

(defn circles-view []
  (auth-site (base-template (circles))))
