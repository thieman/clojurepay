(ns clojurepay.util)

(defn redirect-to [location] {:status 302
                              :headers {"Location" location}})

(defn dispatch-on-method [method & args] method)

(defmacro def-default-method-handler [method]
  `(defmethod ~method :default [& args#] {:status 405}))

(defmacro defsitehandler [sym]
  `(do (defmulti ~sym dispatch-on-method)
       (def-default-method-handler ~sym)))

(defmacro with-args [args & body]
  `(if (every? (complement nil?) ~args)
     (do ~@body)
     {:status 400
      :error (str "Required args: " ~args)}))

(defn swap-keys [in-map old-key new-key]
  (-> in-map
      (assoc new-key (get in-map old-key))
      (dissoc old-key)))

(defn append-get-params [url param-map]
  (let [url (str url "?")
        reducer #(str %1 (name (first %2)) "=" (second %2) "&")]
    (apply str (butlast (reduce reducer url (seq param-map))))))

(defn api-raise
  "Throw an exception if an API call returns anything other than 200."
  [api-result]
  (when (or (nil? api-result) (not (= 200 (:status api-result))))
    (throw (Exception. "API call returned failure status code"))))

(defn random-string [size]
  (let [valid-chars "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"]
    (clojure.string/join (repeatedly size #(rand-nth valid-chars)))))
