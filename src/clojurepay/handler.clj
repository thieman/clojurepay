(ns clojurepay.handler
  (:use compojure.core
        [monger.core :only [connect! set-db! get-db]]
        [clojurepay.config :only [config]]
        clojurepay.views)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]))

(connect! {:host (:mongo-host config) :port (:mongo-port config)})
(set-db! (get-db "clojurepay"))

(defroutes app-routes
  (GET "/" [] (redirect-to "/signup"))
  (GET "/session" {session :session} (session-print-view session))
  (GET "/signup" {session :session} (signup-view session))
  (POST "/do-signup" {session :session params :params} (do-signup-view session params))
  (GET "/login" {session :session} (login-view session))
  (POST "/do-login" {session :session params :params} (do-login-view session params))
  (route/resources "/static/")
  (route/not-found "Not Found"))

(def app
  (handler/site app-routes))
