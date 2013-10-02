(ns clojurepay.views
  (:use [net.cgrand.enlive-html]))

(defsnippet navbar "public/templates/navbar.html" [root] [] identity)

(defsnippet footer "public/templates/footer.html" [root] [] identity)

(defsnippet signup-form "public/templates/signup.html" [root] [] identity)

(deftemplate base-template "public/templates/base.html" [body-content]
  [:#body-content] (content body-content)
  [:#navbar] (substitute (navbar))
  [:#footer] (append (footer)))

(defn index-view [] (base-template "Hello World!"))

(defn signup-view [] (base-template (signup-form)))
