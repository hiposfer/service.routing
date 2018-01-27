(ns hiposfer.kamal.services.webserver.core
  (:require [com.stuartsierra.component :as component]
            [hiposfer.kamal.services.webserver.handlers :as handler]
            [ring.adapter.jetty :as jetty])
  (:import (org.eclipse.jetty.server Server)))

;; --------
;; A Jetty WebServer +  compojure api
(defrecord WebServer [config router server]
  component/Lifecycle
  (start [this]
    (if (:server this) this
      (let [handler (handler/create router)
            server  (jetty/run-jetty handler config)]
        (println "-- Starting App server")
        (assoc this :server server))))
  (stop [this]
    (if-let [server (:server this)]
      (do (println "-- Stopping App server")
          (.stop ^Server server)
          (.join ^Server server)
          (assoc this :server nil)))
    this))

(defn service [] (map->WebServer {}))

