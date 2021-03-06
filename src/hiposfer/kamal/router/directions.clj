(ns hiposfer.kamal.router.directions
  "collection of functions to provide routing directions based on Open Street
  Maps and General Transit Feed Specification data. It follows (as close
  as possible) the MapBox v5 directions specification.

  The algorithm provides a single route between the first and last coordinate.
  It works as follows:
  - create a Dijkstra collection to find the shortest path
  - take the sequence and split it into 'pieces'
   - each piece is a subsection of the complete route with a common 'context'.
    The 'context' is the road name or the stop name
  - loop over the pieces to create instructions based on each previous, current
    and next piece"
  (:require [datascript.core :as data]
            [hiposfer.kamal.router.algorithms.dijkstra :as dijkstra]
            [hiposfer.kamal.router.algorithms.protocols :as np]
            [hiposfer.kamal.router.transit :as transit]
            [hiposfer.kamal.router.util.geometry :as geometry]
            [hiposfer.kamal.router.util.fastq :as fastq])
  (:import (java.time Duration LocalTime ZonedDateTime)
           (java.time.temporal ChronoUnit)))

;; https://www.mapbox.com/api-documentation/#stepmaneuver-object
(def bearing-turns
  (sorted-map 0    "straight"
              20   "slight right"
              60   "right"
              120  "sharp right"
              160  "uturn"
              -20  "sharp left"
              -60  "left"
              -120  "slight left"
              180  "straight"
              -180  "straight"))


(def ->coordinates (juxt np/lon np/lat))

(defn- location [e] (or (:node/location e) [(:stop/lon e) (:stop/lat e)]))

(defn- linestring
  "get a geojson linestring based on the route path"
  [entities]
  {:type "LineString"
   :coordinates (for [e entities] (->coordinates (location e)))})

(defn- modifier
  "return the turn indication based on the angle"
  [angle _type]
  (when (= "turn" _type)
    (val (last (subseq bearing-turns <= angle)))))

;; https://www.mapbox.com/api-documentation/#stepmaneuver-object
(defn- maneuver-type
  [prev-piece piece next-piece]
  (let [last-context (transit/context prev-piece)
        context      (transit/context piece)
        next-context (transit/context next-piece)]
    (cond
      (= prev-piece [(first piece)])
      "depart"

      (= [(last piece)] next-piece)
      "arrive"

      ;; change conditions, e.g. change of mode from walking to transit
      (and (transit/way? last-context) (transit/stop? context))
      "notification"

      ;; already on a transit trip, continue
      (and (transit/stop? context) (transit/stop? next-context))
      "continue"

      ;; change of conditions -> exit vehicle
      (and (transit/stop? context) (transit/way? next-context))
      "exit vehicle"

      :else
      "turn")))

;; https://www.mapbox.com/api-documentation/#stepmaneuver-object
(defn- maneuver ;; piece => [trace ...]
  "returns a step maneuver"
  [prev-piece piece next-piece]
  (let [pre-bearing  (geometry/bearing (location (key (first prev-piece)))
                                       (location (key (first piece))))
        post-bearing (geometry/bearing (location (key (first piece)))
                                       (location (key (first next-piece))))
        angle        (geometry/angle pre-bearing post-bearing)
        _type        (maneuver-type prev-piece piece next-piece)
        _modifier    (modifier angle _type)]
    (merge {:maneuver/bearing_before pre-bearing
            :maneuver/bearing_after  post-bearing
            :maneuver/type _type}
           (when (= _type "turn")
             {:maneuver/modifier _modifier}))))

;https://www.mapbox.com/api-documentation/#routestep-object
(defn- step ;; piece => [trace ...]
  "includes one StepManeuver object and travel to the following RouteStep"
  [zone-midnight prev-piece piece next-piece]
  (let [context (transit/context piece)
        line    (linestring (map key (concat piece [(first next-piece)])))
        man     (maneuver prev-piece piece next-piece)
        mode    (if (transit/stop? context) "transit" "walking")
        arrives (np/cost (val (first piece)))]
    (merge {:step/mode     mode
            :step/distance (geometry/arc-length (:coordinates line))
            :step/geometry line
            :step/maneuver man
            :step/arrive   (+ zone-midnight arrives)}
           (when (not-empty (transit/name context))
             {:step/name (transit/name context)})
           (when (= "notification" (:maneuver/type man))
             {:step/wait (:stop_time/wait (val (first next-piece)))})
           (when (= "transit" mode)
             (let [transit-piece (if (= "exit vehicle" (:maneuver/type man)) piece next-piece)]
               {:step/trip (select-keys (:stop_time/trip (:stop_time/to (val (first transit-piece))))
                                        [:trip/id])})))))

(defn- route-steps
  "returns a route-steps vector or an empty vector if no steps are needed"
  [pieces zone-midnight] ;; piece => [[trace via] ...]
  (let [start     [[(first (first pieces))]]
        ;; use only the last point as end - not the entire piece
        end       [[(last (last pieces))]]
        ;; add depart and arrival pieces into the calculation
        extended  (vec (concat start pieces end))]
    (map step (repeat zone-midnight)
              extended
              (rest extended)
              (rest (rest extended)))))

;https://www.mapbox.com/api-documentation/#route-object
(defn- route
  "a route from the first to the last waypoint. Only two waypoints
  are currently supported"
  [trail zone-midnight]
  (if (= (count trail) 1) ;; a single trace is returned for src = dst
    {:directions/distance 0 :directions/duration 0 :directions/steps []}
    (let [previous    (volatile! (first trail))
          pieces      (partition-by #(transit/namespace (transit/context % previous))
                                     trail)
          departs     (np/cost (val (first trail)))
          arrives     (np/cost (val (last trail)))]
      {:directions/distance (geometry/arc-length (:coordinates (linestring (map key trail))))
       :directions/duration (- arrives departs)
       :directions/steps    (route-steps pieces zone-midnight)})))

;; for the time being we only care about the coordinates of start and end
;; but looking into the future it is good to make like this such that we
;; can always extend it with more parameters
;; https://www.mapbox.com/api-documentation/#retrieve-directions
(defn direction
  "given a network and a sequence of keywordized parameters according to
   https://www.mapbox.com/api-documentation/#retrieve-directions
   returns a directions object similar to the one from Mapbox directions API

   Example:
   (direction network :coordinates [{:lon 1 :lat 2} {:lon 3 :lat 4}]"
  [conn params]
  (let [{:keys [coordinates ^ZonedDateTime departure]} params
        graph   (get (meta conn) :area/graph)
        network (deref conn)
        trips   (fastq/day-trips network (. departure (toLocalDate)))
        init    (Duration/between (LocalTime/MIDNIGHT)
                                  (. departure (toLocalTime)))
        src     (first (fastq/nearest-nodes network (first coordinates)))
        dst     (first (fastq/nearest-nodes network (last coordinates)))]
    (when (and (some? src) (some? dst))
      (let [router (transit/->TransitRouter network graph trips)
            ; both start and dst should be found since we checked that before
            start  [(:db/id src) (. init (getSeconds))]
            path   (dijkstra/shortest-path router #{start} (:db/id dst))
            trail  (for [[id value] (reverse path)]
                     ;; HACK: prefetch the entity so that the rest of the code
                     ;; doesnt. TODO: figure out a better way to do this
                     (first {(data/entity network id) value}))]
        (when (not-empty trail)
          (merge {:directions/uuid      (data/squuid)
          ;; use some for a best guess approach in case the first and last point dont have a way
                  :directions/waypoints
                  [{:waypoint/name     (some (comp :way/name :way/entity val) trail)
                    :waypoint/location (->coordinates (location src))}
                   {:waypoint/name     (some (comp :way/name :way/entity val) (reverse trail))
                    :waypoint/location (->coordinates (location dst))}]}
                 (route trail (-> departure (.truncatedTo ChronoUnit/DAYS)
                                            (.toEpochSecond)))))))))

;(dotimes [n 1000]
#_(time (direction (first @(:networks (:router hiposfer.kamal.dev/system)))
                   {:coordinates [[8.645333, 50.087314]
                                  ;[8.680412, 50.116680] ;; innenstadt
                                  ;[8.699619, 50.097842]] ;; sachsenhausen
                                  [8.635897, 50.104172]] ;; galluswarte
                    :departure   (ZonedDateTime/parse "2018-05-07T10:15:30+02:00")}))
