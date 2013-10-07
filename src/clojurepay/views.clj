(ns clojurepay.views
  (:use net.cgrand.enlive-html
        sandbar.stateful-session
        [clojurepay.config :only [config]]
        [clojurepay.util :only [redirect-to with-args append-get-params]]
        clojurepay.auth
        monger.operators)
  (:require [monger.collection :as mc]
            [clojurepay.api :as api]
            [clj-time.format :as fmt]
            [clj-http.client :as client]
            [cheshire.core :as cheshire])
  (:import [org.bson.types ObjectId]))

(def time-formatter (fmt/formatters :year-month-day))

(defn unparse
  "Like clj-time.unparse, but returns empty string if passed nil.
   Default behavior returns the current time, which is f*#$ing stupid."
  [fmt val]
  (if (nil? val) "" (fmt/unparse fmt val)))

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

(defsnippet css "public/templates/css.html" [:link] [stylesheet]
  [:link] (set-attr :href (str "/static/css/" stylesheet)))

(deftemplate base-template*
  "public/templates/base.html" [body-content stylesheets]
  [:#body-content] (content body-content)
  [:head] (append (map css stylesheets))
  [:#navbar] (substitute (navbar))
  [:#footer] (substitute (footer)))

(defn base-template
  ([body-content] (base-template [] body-content))
  ([stylesheets body-content] (base-template* body-content stylesheets)))

(defsnippet signup-form
  "public/templates/signup-form.html" [:form] [form-action msg]
  [:form] (do-> (prepend (alert msg "warning"))
                (set-attr :action form-action)))

(defsnippet login-form
  "public/templates/login-form.html" [:form] [form-action msg]
  [:form] (do-> (prepend (alert msg "warning"))
                (set-attr :action form-action)))

(defsnippet add-circle-form
  "public/templates/add-circle-form.html" [:form] [form-action]
  [:form] (set-attr :action form-action))

(defsnippet circle-table-element
  "public/templates/circle-table-element.html" [:tr] [circle-doc]
  [:.name] (content (:name circle-doc))
  [:.owner] (content (get-in circle-doc [:owner :name]))
  [:.updated] (content (unparse time-formatter (:updated circle-doc)))
  [:.nav :a] (set-attr :href (str "/circle/" (:_id circle-doc))))

(defsnippet circles "public/templates/circles.html" [root] [circles-doc msg]
  [:.alert] (when msg (substitute (alert msg "warning")))
  [:#add-new] (after (add-circle-form "/add-circle"))
  [:tbody] (content (map circle-table-element circles-doc)))

(defsnippet member-table-element*
  "public/templates/member-table-element.html" [:tr] [circle-doc user-doc]
  [:.name] (content (:name user-doc))
  [:.last] (content (unparse time-formatter (:last user-doc)))
  [:.remove-member] (when (and (owns-circle? circle-doc) (not (self? user-doc)))
                      identity))

(defn member-table-element [circle-doc user-partial-doc]
  (let [user-doc (mc/find-map-by-id "user" (:id user-partial-doc))]
    (member-table-element* circle-doc (merge user-partial-doc user-doc))))

(defsnippet charge-circle-form
  "public/templates/charge-circle-form.html" [:form] []
  identity)

(defsnippet circle-detail
  "public/templates/circle-detail.html" [root] [circle-doc]
  [:#circle-name] (content (:name circle-doc))
  [:.charge] (content (charge-circle-form))
  [:tbody] (content (map (partial member-table-element circle-doc) (:users circle-doc))))

(defn index-redirect []
  (redirect-to (if (logged-in?) "/circles" "/signup")))

(defn signup-view
  ([] (signup-view nil))
  ([msg] (base-template (signup-form "do-signup" msg))))

(defn venmo-auth-redirect
  "Synchronous for now, should really not be. But hey, hacking."
  [{code :code}]
  (let [venmo-response
        (client/post (:venmo-token-url config)
                     {:form-params {:client_id (:venmo-client-id config)
                                    :client_secret (:venmo-client-secret config)
                                    :code code}})
        venmo-doc (cheshire/parse-string (:body venmo-response))]
    (if (nil? venmo-doc)
      {:status 400}
      (do (mc/insert "venmo" venmo-doc)
          (mc/update-by-id "user"
                           (session-get :user)
                           {$set {:active true
                                  :token (get venmo-doc "access_token")}})
          (redirect-to "/")))))

(defn login-view
  ([] (login-view nil))
  ([msg] (base-template (login-form "do-login" msg))))

(defn do-signup-view [params]
  (if (email-exists? (:email params))
    (signup-view "A user with this email already exists.")
    (try
      (save-new-user (:name params) (:email params) (:password params))
      (login-user (:email params))
      (redirect-to (append-get-params (:venmo-auth-url config)
                                      {:client_id (:venmo-client-id config)
                                       :scope "make_payments"
                                       :response_type "code"}))
      (catch Exception e
        (signup-view "There was an error creating your account. Please try again.")))))

(defn do-login-view [email password]
  (if (password-is-correct? email password)
    (do
      (login-user email)
      (redirect-to "/"))
    (login-view "Incorrect email or password.")))

(defn logout-view []
  (destroy-session!)
  (redirect-to "/"))

(defn circles-view
  ([] (circles-view nil))
  ([msg] (base-template ["circles.css"] (circles (:body (api/circles :get)) msg))))

(defn circle-view [id]
  (let [circle-doc (mc/find-map-by-id "circle" (ObjectId. id))]
    (with-args [circle-doc]
      (if-not (is-member? circle-doc)
        {:status 401}
        (base-template ["circle-detail.css"] (circle-detail circle-doc))))))

(defn add-circle [{name :name}]
  (with-args [name]
    (let [result (api/circle :post name)]
      (if (= (:status result) 200)
        (redirect-to "/circles")
        (circles-view "Something went wrong when creating your circle, please try again.")))))

(defn leave-circle [{name :name}] name)
