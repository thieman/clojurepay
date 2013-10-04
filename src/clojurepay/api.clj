(ns clojurepay.api
  (:use [net.cgrand.enlive-html]
        ring.util.response
        sandbar.stateful-session
        [clojurepay.config :only [config]])
  (:require [monger.collection :as mc]))

(defn dispatch-on-method [method & args] [method])

(defmacro def-default-method-handler [method]
  `(defmethod ~method :default [& args#] {:status 405}))

(defn def-default-method-handlers [& methods]
  (doseq [method methods]
    (def-default-method-handler method)))

(defmulti circles dispatch-on-method)

(defmethod circles [:get] [method params]
  ;; Return all circles of which the user is a member.
  (response {:hooray "puffin!"}))

(defmethod circles [:post] [method params]
  ;; Create a new circle with the creating user as its only member.
  (response {:hooray "snacks!"}))

(defmulti circle dispatch-on-method)

(defmethod circle [:get] [method params id]
  ;; Return information on a given circle.
  (response {:hooray "steve!"}))

(def-default-method-handlers circles circle)
