(ns hiposfer.kamal.dev
  "Tools for interactive development with the REPL. This file should
  not be included in a production build of the application."
  (:require [com.stuartsierra.component :as component]
            [environ.core :refer [env]]
            [hiposfer.kamal.core :as routing]
            [clojure.tools.namespace.repl :as repl]))
            ;[cheshire.core :as cheshire]))

(defonce system nil)

(defn init!
  "Constructs the current development system."
  []
  (alter-var-root #'system
    (constantly (routing/system (routing/config {:dev false})))))

(defn start!
  "Starts the current development system."
  []
  (alter-var-root #'system component/start))

(defn stop!
  "Shuts down and destroys the current development system."
  []
  (alter-var-root #'system (fn [s] (when s (component/stop s)))))

(defn go!
  "Initializes the current development system and starts it running."
  []
  (when system ;; prevent leaking resources if already started
    (stop!))
  (init!)
  (start!))

;; WARN: this will fail if you just ran `lein uberjar` without
;; cleaning afterwards. See
;; https://stackoverflow.com/questions/44246924/clojure-tools-namespace-refresh-fails-with-no-namespace-foo
(defn reset
  "reset the system to a fresh state. Prefer using this over go!"
  []
  (stop!)
  (repl/refresh :after 'hiposfer.kamal.dev/go!))

;(reset)

;(set! *print-length* 50)
;(take 10 (:graph @(:network (:grid system))))

;(System/gc)

;(time (cheshire/generate-stream @(:network (:grid system))
;        (clojure.java.io/writer "resources/saarland.json")))
;
;(time (last (cheshire/parse-stream
;              (clojure.java.io/reader "resources/saarland.json"))))