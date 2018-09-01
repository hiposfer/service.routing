(ns hiposfer.kamal.libs.fastq
  "namespace for hand-optimized queries that are used inside the routing
  algorithm and need to run extremely fast (< 1 ms per query)

  By convention all queries here return Entities"
  (:require [datascript.core :as data]
            [hiposfer.kamal.libs.tool :as tool])
  (:import (java.time LocalDate)))

(defn index-lookup
  "returns a transducer that can be used together with index-range to get all
  entities whose value equals id i.e. the entities that have a reference to id"
  [network id]
  (comp (take-while #(= (:v %) id))
        (map #(data/entity network (:e %)))))

(defn node-successors
  "takes a network and an entity and returns the successors of that entity.
   Only valid for OSM nodes. Assumes bidirectional links i.e. nodes with
   back-references to entity are also returned

  replaces:
  '[:find ?successors ?node
    :in $ ?id
    :where [?id :node/successors ?successors]
           [?node :node/successors ?id]]

  The previous query takes around 50 milliseconds to finish. This function
  takes around 0.25 milliseconds"
  [network entity]
  (let [id (:db/id entity)]
    (concat (:node/successors entity)
            (sequence (index-lookup network id)
                      (data/index-range network :node/successors id nil)))))


(defn nearest-node
  "returns the nearest node/location to point"
  [network point]
  (map #(data/entity network (:e %))
        (data/index-range network :node/location point nil)))

(defn node-ways
  "takes a dereferenced Datascript connection and an entity id and returns
  the OSM ways that reference it. Only valid for OSM node ids

  replaces:
  '[:find ?way
    :in $ ?id
    :where [?way :way/nodes ?id]]

  The previous query takes around 50 milliseconds to finish. This one takes
  around 0.15 milliseconds"
  [network entity]
  (let [id (:db/id entity)]
    (sequence (index-lookup network id)
              (data/index-range network :way/nodes id nil))))

(defn continue-trip
  "returns the :stop_times entity to reach ?dst-id via ?trip

  Returns nil if no trip going to ?dst-id was found

  replaces:
  '[:find ?departure
    :in $ ?dst-id ?trip ?start
    :where [?dst :stop_times/stop ?dst-id]
           [?dst :stop_times/trip ?trip]
           [?dst :stop_times/arrival_time ?seconds]
           [(plus-seconds ?start ?seconds) ?departure]]

   The previous query takes around 50 milliseconds to execute. This function
   takes around 0.22 milliseconds to execute. Depends on :stop_times/trip index"
  [network dst trip]
  (let [?dst-id  (:db/id dst)
        ?trip-id (:db/id trip)]
    (tool/some #(= ?dst-id (:db/id (:stop_times/stop %)))
               (eduction (index-lookup network ?trip-id)
                         (data/index-range network :stop_times/trip ?trip-id nil)))))

(defn find-trip
  "Returns a [src dst] :stop_times pair for the next trip between ?src-id
   and ?dst-id departing after ?now.

   Returns nil if no trip was found

  replaces:
  '[:find ?trip ?departure
    :in $ ?src-id ?dst-id ?now ?start
    :where [?src :stop_times/stop ?src-id]
           [?dst :stop_times/stop ?dst-id]
           [?src :stop_times/trip ?trip]
           [?dst :stop_times/trip ?trip]
           [?src :stop_times/departure_time ?amount]
           [(hiposfer.kamal.libs.fastq/plus-seconds ?start ?amount) ?departure]
           [(hiposfer.kamal.libs.fastq/after? ?departure ?now)]]

  The previous query runs in 118 milliseconds. This function takes 4 milliseconds"
  [network trips src dst now]
  (let [?src-id (:db/id src)
        stop_times (eduction (index-lookup network ?src-id)
                             (filter #(contains? trips (:db/id (:stop_times/trip %))))
                             (filter #(> (:stop_times/departure_time %) now))
                             (data/index-range network :stop_times/stop ?src-id nil))]
    (when (not-empty stop_times)
      (let [trip (apply min-key :stop_times/departure_time stop_times)]
        [trip (continue-trip network dst (:stop_times/trip trip))]))))

(defn day-trips
  "returns a set of trip (entities) ids that are available for date"
  [network ^LocalDate date]
  (let [services (into #{} (comp (take-while #(= (:a %) :service/id))
                                 (map #(data/entity network (:e %)))
                                 (filter #(and (.isBefore date (:service/end_date %))
                                               (.isAfter date (:service/start_date %))
                                               (contains? (:service/days %)
                                                          (.getDayOfWeek date))))
                                 (map :db/id))
                           (data/seek-datoms network :avet :service/id))]
    (into #{} (comp (take-while #(= (:a %) :trip/service))
                    (filter #(contains? services (:v %)))
                    (map :e))
              (data/seek-datoms network :avet :trip/service))))

(defn- references
  "returns all entities that reference entity through attribute k"
  ([network k entity]
   (references network k entity (map identity)))
  ([network k entity xform]
   (eduction (comp (index-lookup network (:db/id entity))
                   xform)
             (data/index-range network k (:db/id entity) nil))))

(defn next-stops
  "return the next stop entities for ?src-id based on :stop_times

  This function might return duplicates

  replaces:
  '[:find [?id ...]
    :in $ ?src-id
    :where [?src :stop_times/stop ?src-id]
           [?src :stop_times/trip ?trip]
           [?dst :stop_times/trip ?trip]
           [?src :stop_times/sequence ?s1]
           [?dst :stop_times/sequence ?s2]
           [(> ?s2 ?s1)]
           [?dst :stop_times/stop ?se]
           [?se :stop/id ?id]]
  the previous query takes 145 milliseconds. This function takes 0.2 milliseconds"
  [network src]
  (for [st1  (references network :stop_times/stop src)
        :let [stimes2 (references network
                                  :stop_times/trip
                                  (:stop_times/trip st1)
                                  (filter #(> (:stop_times/sequence %)
                                              (:stop_times/sequence st1))))]
        :when (not-empty stimes2)]
      (:stop_times/stop (apply min-key :stop_times/sequence stimes2))))

;(time
;  (dotimes [n 10000]
;    (next-stops @(first @(:networks (:router hiposfer.kamal.dev/system)))
;                (data/entity @(first @(:networks (:router hiposfer.kamal.dev/system))) 230963))))


;; This might not be the best approach but it gets the job done for the time being
(defn link-stops
  "takes a network, looks up the nearest node for each stop and returns
  a transaction that will link those"
  [network]
  (for [stop (map #(data/entity network (:e %))
                  (data/datoms network :aevt :stop/id))]
    (let [node (first (nearest-node network (:stop/location stop)))]
      (if (not (some? node))
        (throw (ex-info "stop didnt match to any known node in the OSM data"
                        (into {} stop)))
        {:node/id (:node/id node)
         :node/successors #{[:stop/id (:stop/id stop)]}}))))

;; this reduced my test query from 30 seconds to 8 seconds
(defn cache-stop-successors
  "computes the next-stops for each stop and returns a transaction
  that will cache those results inside the :stop entities"
  [network]
  (for [stop (map #(data/entity network (:e %))
                  (data/datoms network :aevt :stop/id))]
    (let [neighbours (distinct (next-stops network stop))]
      {:stop/id (:stop/id stop)
       :stop/successors (for [n neighbours] [:stop/id (:stop/id n)])})))
