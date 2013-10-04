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

(defroutes public-routes*
  (GET "/" [] (views/index-redirect))
  (GET "/signup" [] (views/signup-view))
  (POST "/do-signup" {params :params} (views/do-signup-view params))
  (GET "/login" [] (views/login-view))
  (POST "/do-login" {params :params} (views/do-login-view params))
  (GET "/authed" [] (views/authed-view)))

(defroutes private-routes*
  (GET "/logout" [] (views/logout-view))
  (GET "/circles" [] (views/circles-view)))

(defroutes api-routes*
  (ANY "/api/circles"
       {method :request-method params :params}
       (api/circles method params))
  (ANY "/api/circle/:id"
       {method :request-method params :params id :id}
       (api/circle method params id)))

(defroutes site-routes*
  (route/resources "/static/")
  (route/not-found "Not Found"))

(def public-routes (handler/site public-routes*))
(def private-routes (handler/site private-routes*))
(def api-routes (wrap-json-response (handler/api api-routes*)))
(def site-routes site-routes*)

(defroutes main-routes
  (ANY "*" [] public-routes)
  (ANY "*" [] (if (auth/logged-in?) private-routes (views/index-redirect)))
  (ANY "*" [] (if (auth/logged-in?) api-routes {:status 401}))
  (ANY "*" [] site-routes))

(def app
  (wrap-stateful-session main-routes))
