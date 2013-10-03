(ns clojurepay.handler
  (:use compojure.core
        sandbar.stateful-session
        [monger.core :only [connect! set-db! get-db]]
        [clojurepay.config :only [config]]
        clojurepay.views)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]))

(connect! {:host (:mongo-host config) :port (:mongo-port config)})
(set-db! (get-db "clojurepay"))

(defroutes app-routes
  (GET "/" [] (index-redirect))
  (GET "/session" [] (session-print-view))
  (GET "/signup" [] (signup-view))
  (POST "/do-signup" {params :params} (do-signup-view params))
  (GET "/login" [] (login-view))
  (POST "/do-login" {params :params} (do-login-view params))
  (GET "/logout" [] (logout-view))
  (route/resources "/static/")
  (route/not-found "Not Found"))

(def app
  (-> app-routes
      (handler/site)
      (wrap-stateful-session)))
