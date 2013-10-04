(ns clojurepay.helpers)

(defn redirect-to [location] {:status 302
                              :headers {"Location" location}})
