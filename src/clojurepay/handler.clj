(ns clojurepay.handler
  (:use compojure.core
        sandbar.stateful-session
        ring.middleware.json
        [monger.core :only [connect! set-db! get-db]]
        [clojurepay.config :only [config]])
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [clojurepay.auth :as auth]
            [clojurepay.views :as views]
            [clojurepay.api :as api]))

(connect! {:host (:mongo-host config) :port (:mongo-port config)})
(set-db! (get-db "clojurepay"))

(def object-id #"[0-9a-zA-Z]{24}")
(def invite-code #"[0-9a-zA-Z]{20}")

(defroutes public-routes*
  (GET "/" [] (views/index-redirect))
  (GET "/signup" [] (views/signup-view))
  (POST "/do-signup" {params :params} (views/do-signup-view params))
  (GET "/login" [] (views/login-view))
  (POST "/do-login" [email password] (views/do-login-view email password)))

(defroutes private-routes*
  (POST "/add-circle" {params :params} (views/add-circle params))
  (POST "/leave-circle" {params :params} (views/leave-circle params))
  (GET "/auth-redirect" {params :params} (views/venmo-auth-redirect params))
  (GET "/logout" [] (views/logout-view))
  (GET "/circles" [] (views/circles-view))
  (GET ["/circle/:id" :id object-id] {params :params}
       (views/circle-view params))
  (GET ["/circle/join/:id/:code" :id object-id :code invite-code]
       [id code] (views/join-circle-view id code))
  (POST "/do-join" [id code] (views/do-join-circle id code))
  (POST "/do-charge" [id amount memo] (views/do-charge-circle id amount memo))
  (POST "/do-remove-member" [circle-id user-id]
        (views/do-remove-member circle-id user-id)))

(defroutes api-routes*

  (ANY "/api/circles"
        {method :request-method}
        (api/circles method))

  (POST "/api/circle" [name] (api/circle :post name))
  (ANY "/api/circle/:id"
       {method :request-method params :params}
       (api/circle method (:id params)))

  (POST "/api/circle/reassign-owner" [circle-id user-id]
        (api/circle-reassign-owner circle-id user-id))
  (POST "/api/circle/remove-member" [circle-id user-id]
        (api/circle-remove-member circle-id user-id))
  (POST "/api/circle/join" [circle-id invite-code]
        (api/circle-join circle-id invite-code)))

(def public-routes public-routes*)
(def private-routes private-routes*)
(def api-routes (-> api-routes*
                    (wrap-json-response)
                    (wrap-json-params)))

(defroutes main-routes
  (route/resources "/static/")
  (ANY "/api/*" [] (if (auth/logged-in?) api-routes {:status 401}))
  (ANY "*" [] public-routes)
  (ANY "*" [] (if (auth/logged-in?) private-routes (views/index-redirect)))
  (route/not-found {:status 404}))

(def app
  (wrap-stateful-session (handler/site main-routes)))
