(ns clojurepay.config)

(def config {:client_id (-> (clojure.java.io/resource "private/client_id")
                            (slurp)
                            (clojure.string/trimr)
                            (Integer/parseInt))
             :client_secret (-> (clojure.java.io/resource "private/client_secret")
                                (slurp)
                                (clojure.string/trimr))})
