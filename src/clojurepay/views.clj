(ns clojurepay.views
  (:use [net.cgrand.enlive-html]
        [clojurepay.config :only [config]]
        clojurepay.auth)
  (:require [monger.collection :as mc]))

(defn redirect-to [location] {:status 302
                              :headers {"Location" location}})

(defsnippet navbar-link "public/templates/navbar-link.html" [:li] [id href text]
  [:li] (set-attr :id id)
  [:a] (do-> (set-attr :href href)
             (content text)))

(defsnippet navbar "public/templates/navbar.html" [:.navbar] [session]
  [:#navbar-right] (if (logged-in? session)
                     (append (navbar-link "logout" "/logout" "Log Out"))
                     (append (navbar-link "login" "/login" "Log In"))))

(defsnippet footer "public/templates/footer.html" [root] [] identity)

(defsnippet alert "public/templates/alert.html" [:.alert] [msg class]
  [:.alert] (do-> (add-class (str "alert-" (if (nil? msg) "hidden" class)))
                  (content msg)))

(deftemplate base-template "public/templates/base.html" [session body-content]
  [:#body-content] (content body-content)
  [:#navbar] (substitute (navbar session))
  [:#footer] (substitute (footer)))

(defsnippet signup-form "public/templates/signup-form.html" [:form] [form-action msg]
  [:form] (do-> (prepend (alert msg "warning"))
                (set-attr :action form-action)))

(defsnippet login-form "public/templates/login-form.html" [:form] [form-action msg]
  [:form] (do-> (prepend (alert msg "warning"))
                (set-attr :action form-action)))

(defn session-print-view [session] (base-template session))

(defn signup-view
  ([session] (signup-view session nil))
  ([session msg] (base-template session (signup-form "do-signup" msg))))

(defn login-view
  ([session] (login-view session nil))
  ([session msg] (base-template session (login-form "do-login" msg))))

(defn do-signup-view [session params]
  (if (email-exists? (:email params))
    (signup-view session "A user with this email already exists.")
    (do
      (save-new-user (:name params) (:email params) (:password params))
      (base-template session params))))

(defn do-login-view [session params]
  (if (password-is-correct? (:email params) (:password params))
    (assoc (redirect-to "/") :session (login-user session))
    (login-view session "Incorrect email or password.")))

(defn logout-view [session]
  (assoc (redirect-to "/") :session nil))
