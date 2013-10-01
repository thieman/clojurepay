(ns clojurepay.config)

(def config {:client_id 1417
             :client_secret (clojure.string/trimr (slurp (clojure.java.io/resource "private/client_secret")))})
