(defproject clojurepay "0.1.0"
  :description "Demand money from your friends! Using Clojure!"
  :url "http://clojurepay.travisthieman.com"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [compojure "1.1.5"]
                 [timewarrior/sandbar "0.5.0"]
                 [ring/ring-json "0.2.0"]
                 [clj-time "0.6.0"]
                 [enlive "1.1.4"]
                 [cheshire "5.2.0"]
                 [clojurewerkz/scrypt "1.0.0"]
                 [clj-http "0.7.7"]
                 [com.novemberain/monger "1.7.0-beta1"]]
  :plugins [[lein-ring "0.8.5"]]
  :ring {:handler clojurepay.handler/app}
  :profiles
  {:dev {:dependencies [[ring-mock "0.1.5"]]}})
