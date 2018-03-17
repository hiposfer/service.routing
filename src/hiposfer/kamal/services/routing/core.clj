(ns hiposfer.kamal.services.routing.core
  (:require [hiposfer.kamal.parsers.osm :as osm]
            [hiposfer.kamal.network.generators :as ng]
            [clojure.test.check.generators :as gen]
            [hiposfer.kamal.parsers.gtfs.core :as gtfs]
            [datascript.core :as data]
            [com.stuartsierra.component :as component]
            [hiposfer.kamal.network.algorithms.core :as alg]
            [hiposfer.kamal.libs.fastq :as fastq]))

;; NOTE: we use :db/index true to replace the lack of :VAET index in datascript
;; This is for performance. In lots of cases we want to lookup back-references
;; to a certain datom. Without indexes it is not possible to find it without
;; traversing most (all?) of the db.
;; The same kind of behavior can be achieved by combining entity with index-range
;; such that the queries can still be answered pretty fast :)

;; NOTE: we use a node/location instead of lat, lon separate to avoid creating
;; so many datoms and to have a single entity that we can apply protocols against.
;; This is a hack !! but it works :)
;; Thanks datascript

(def schema {;; Open Street Map - entities
             :node/id         {:db.unique :db.unique/identity}
             :node/location   {:db/index true}
             :node/successors {:db.type        :db.type/ref
                               :db.cardinality :db.cardinality/many
                               :db/index true}

             :way/id          {:db.unique :db.unique/identity}
             :way/nodes       {:db.type        :db.type/ref
                               :db.cardinality :db.cardinality/many
                               :db/index true}

             ;; General Transfer Feed Specification - entities
             :trip/id         {:db.unique :db.unique/identity}
             :trip/route      {:db/type :db.type/ref}
             :trip/service    {:db/type :db.type/ref}

             :agency/id       {:db.unique :db.unique/identity}

             :service/id      {:db.unique :db.unique/identity}

             :route/id        {:db.unique :db.unique/identity}
             :route/agency    {:db/type :db.type/ref}

             :stop/id         {:db.unique :db.unique/identity}
             :stop/successors {:db.type :db.type/ref
                               :db.cardinality :db.cardinality/many}
             :stop/location   {:db/index true}

             :stop.times/trip  {:db/type :db.type/ref
                                :db/index true}
             :stop.times/stop  {:db/type :db.type/ref
                                :db/index true}})

;; This might not be the best approach but it gets the job done for the time being
(defn link-stops
  "takes a network, looks up the nearest node for each stop and returns
  a transaction that will link those"
  [network]
  (for [stop (map #(data/entity network (:e %))
                   (data/datoms network :aevt :stop/id))]
    (let [node (first (fastq/nearest-node network (:stop/location stop)))]
      {:node/id (:node/id node)
       :node/successors #{(:db/id stop)}})))

;; this reduced my test query from 30 seconds to 8 seconds
(defn cache-stop-successors
  "computes the next-stops for each stop and returns a transaction
  that will cache those results inside the :stop entities"
  [network]
  (for [stop (map #(data/entity network (:e %))
                   (data/datoms network :aevt :stop/id))]
    (let [neighbours (fastq/next-stops network stop)]
      {:stop/id (:stop/id stop)
       :stop/successors (map :db/id neighbours)})))

(defn exec!
  "builds a datascript in-memory db and conj's it into the passed agent"
  [ag area dev]
  (println "-- starting area router:" area)
  (let [vehicle     (when (and (not dev) (:gtfs area))
                      (gtfs/datomize (:gtfs area)))
        pedestrian  (if (true? dev)
                      (let [graph (gen/generate (ng/graph (:size area)))]
                        (concat graph (ng/ways (alg/nodes graph))))
                      (time (osm/datomize (:osm area))))
        conn        (data/create-conn schema)]
    (time (data/transact! conn (concat pedestrian vehicle)))
    (data/transact! conn (link-stops @conn))
    (data/transact! conn (cache-stop-successors @conn))
    (conj ag conn)))

(defrecord Router [config networks]
  component/Lifecycle
  (start [this]
    (if (not-empty (:networks this)) this
      (let [ag (agent #{} :error-handler println
                          :error-mode :fail)]
        (run! #(send-off ag exec! % (:dev config))
               (:networks config))
        (assoc this :networks ag))))
  (stop [this]
    (println "-- stopping router")
    (assoc this :networks nil)))

(defn service
  "returns a Router record that will contain the config
   of and all the networks of the system as agents"
  [] (map->Router {}))
