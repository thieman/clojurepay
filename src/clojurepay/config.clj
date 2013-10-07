(ns clojurepay.config)

(defn read-file [filepath]
  (-> (clojure.java.io/resource filepath)
      (slurp)
      (clojure.string/trimr)))

(def config {:app-secret (read-file "private/app_secret")
             :venmo-auth-url "https://api.venmo.com/oauth/authorize"
             :venmo-token-url "https://api.venmo.com/oauth/access_token"
             :venmo-client-id (-> (read-file "private/client_id")
                                  (Integer/parseInt))
             :venmo-client-secret (read-file "private/client_secret")
             :mongo-host "127.0.0.1"
             :mongo-port 27017})
