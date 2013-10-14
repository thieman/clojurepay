(ns clojurepay.views
  (:use net.cgrand.enlive-html
        sandbar.stateful-session
        [clojurepay.config :only [config]]
        [clojurepay.util :only [redirect-to with-args append-get-params api-raise]]
        clojurepay.auth
        clojurepay.model
        monger.operators)
  (:require [clojurepay.api :as api]
            [clojurepay.venmo :as venmo]
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
  [:.nav :a] (set-attr :href (str "/circle/" (:_id circle-doc)))
  [:.nav :form] (set-attr :action "/leave-circle")
  [:.nav [:input (attr= :name "id")]] (set-attr :value (str (:_id circle-doc))))

(defsnippet circles "public/templates/circles.html" [root] [circles-doc msg]
  [:.alert] (when msg (substitute (alert msg "warning")))
  [:#add-new] (after (add-circle-form "/add-circle"))
  [:tbody] (content (map circle-table-element circles-doc)))

(defsnippet member-table-element*
  "public/templates/member-table-element.html" [:tr] [circle-doc user-doc include-remove]
  [:.name] (content (:name user-doc))
  [:.last] (content (unparse time-formatter (:last user-doc)))
  [[:input (attr= :name "circle-id")]] (set-attr :value (str (:_id circle-doc)))
  [[:input (attr= :name "user-id")]] (set-attr :value (:_id user-doc))
  [:form] (when include-remove (set-attr :action "/do-remove-member")))

(defn member-table-element [circle-doc user-partial-doc]
  (let [user-doc (fetch (->User) (:id user-partial-doc))]
    (member-table-element* circle-doc
                           (merge user-partial-doc user-doc)
                           (and (owns-circle? circle-doc) (not (self? user-doc))))))

(defsnippet charge-circle-form
  "public/templates/charge-circle-form.html" [:form] [circle-doc]
  [:form] (set-attr :action "/do-charge")
  [[:input (attr= :name "id")]] (set-attr :value (str (:_id circle-doc))))

(defn invite-url [circle-id invite-code]
  (clojure.string/join "/" [(str (:host-path config))
                            "circle"
                            "join"
                            circle-id
                            invite-code]))

(defsnippet circle-detail
  "public/templates/circle-detail.html" [root] [circle-doc alert-msg alert-class]
  [:.alert] (when alert-msg (substitute (alert alert-msg alert-class)))
  [:#circle-name] (content (:name circle-doc))
  [:.charge] (content (charge-circle-form circle-doc))
  [[:input (attr= :name "invite-code")]] (set-attr :value
                                                   (invite-url (str (:_id circle-doc))
                                                               (:invite_code circle-doc)))
  [:tbody] (content (map (partial member-table-element circle-doc) (:users circle-doc))))

(defsnippet join-form
  "public/templates/join-form.html" [root] [circle-doc]
  [:#circle-name] (content (:name circle-doc))
  [:#circle-owner] (content (get-in circle-doc [:owner :name]))
  [:form] (set-attr :action "/do-join")
  [[:input (attr= :name "id")]] (set-attr :value (str (:_id circle-doc)))
  [[:input (attr= :name "code")]] (set-attr :value (:invite_code circle-doc)))

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
      (do (-> (fetch (->User) (session-get :user))
              (update {$set {:active true
                             :token (get venmo-doc "access_token")}}))
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

(defn circle-view
  ([params] (circle-view (:id params) params))
  ([id params] (circle-view id params nil))
  ([id params msg]
     (let [circle (fetch (->Circle) id)
           alert-msg (get params :msg msg)
           alert-class (get params :class "warning")]
       (with-args [circle]
         (if-not (is-member? circle)
           {:status 401}
           (base-template ["circle-detail.css"] (circle-detail circle alert-msg alert-class)))))))

(defn add-circle [{name :name}]
  (with-args [name]
    (let [result (api/circle :post name)]
      (if (= (:status result) 200)
        (redirect-to "/circles")
        (circles-view "Something went wrong when creating your circle, please try again.")))))

(defn do-remove-member [circle-id user-id]
  (let [result (api/circle-remove-member circle-id user-id)]
    (try
      (api-raise result)
      (redirect-to (str "/circle/" circle-id))
      (catch Exception e
        (redirect-to (append-get-params (str "/circle/" circle-id)
                                        {:msg "There was an error removing this member, please try again."
                                         :class "warning"}))))))

(defn leave-circle
  "Removes the current user from the circle.  If user is the owner,
   reassigns owner to the oldest member.  If user is the only member,
   deletes the circle."
  [{circle-id :id}]
  (let [circle (fetch (->Circle) circle-id)
        delete-circle (= 1 (count (:users circle)))
        reassign-owner (= (session-get :user) (get-in circle [:owner :id]))
        process-leave (fn []
                        (if delete-circle
                          (api-raise (api/circle :delete circle-id))
                          (do (when reassign-owner
                                (api-raise (api/circle-reassign-owner
                                            circle-id (session-get :user))))
                              (api-raise (api/circle-remove-member
                                          circle-id (session-get :user))))))]
    (if (nil? circle)
      {:status 400}
      (try
        (process-leave)
        (redirect-to "/circles")
        (catch Exception e
          (circles-view "Something went wrong when leaving this circle, please try again."))))))

(defn join-circle-view [circle-id invite-code]
  (let [circle (fetch (->Circle) circle-id)]
    (with-args [circle]
      (if-not (= invite-code (:invite_code circle))
        (redirect-to "/")
        (base-template (join-form circle))))))

(defn do-join-circle [circle-id invite-code]
  (let [result (api/circle-join circle-id invite-code)]
    (try
      (api-raise result)
      (redirect-to "/circles")
      (catch Exception e
        (circles-view "Something went wrong when joining the circle, please try again.")))))

(defn do-charge-circle [circle-id amount memo]
  (let [circle-doc (fetch (->Circle) circle-id)
        user-doc (fetch (->User) (session-get :user))
        parsed-amount (try (Float/parseFloat amount) (catch Exception e 0))
        valid (and (is-member? circle-doc)
                   (:active user-doc)
                   (> parsed-amount 0))]
    (if-not valid
      (redirect-to (append-get-params (str "/circle/" circle-id)
                                      {:msg "You are unable to charge this circle at this time."
                                       :class "warning"}))
      (do (venmo/charge-circle! circle-id (session-get :user) parsed-amount memo)
          (redirect-to (append-get-params (str "/circle/" circle-id)
                                          {:msg "Successful! Your charges should post within the next few minutes."
                                           :class "success"}))))))
