(ns clojurepay.util)

(defn redirect-to [location] {:status 302
                              :headers {"Location" location}})

(defn dispatch-on-method [method & args] [method])

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
