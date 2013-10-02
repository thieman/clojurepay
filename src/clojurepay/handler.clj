(ns clojurepay.handler
  (:use compojure.core
        [clojurepay.config :only [config]]
        clojurepay.views)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]))

(defroutes app-routes
  (GET "/" [] (index-view))
  (GET "/signup" [] (signup-view))
  (route/resources "/static/")
  (route/not-found "Not Found"))

(def app
  (handler/site app-routes))
