(ns hiposfer.kamal.network.benchmark
  (:require [criterium.core :as c]
            [clojure.test :as test]
            [clojure.spec.gen.alpha :as gen]
            [hiposfer.kamal.network.generators :as ng]
            [hiposfer.kamal.network.algorithms.core :as alg]
            [hiposfer.kamal.preprocessor :as preprocessor]
            [hiposfer.kamal.libs.geometry :as geometry]
            [hiposfer.kamal.services.routing.core :as router]
            [hiposfer.kamal.libs.fastq :as fastq]
            [hiposfer.kamal.network.tests :as kt]
            [datascript.core :as data]
            [taoensso.timbre :as timbre]))

;; This is just to show the difference between a randomly generated network
;; and a real-world network. The randomly generated network does not have a structure
;; meant to go from one place to the other, thus Dijkstra almost always fails to
;; finds a path (really fast)
(test/deftest ^:benchmark dijkstra-random-graph
  (let [dt      (gen/generate (ng/graph 1000))
        network (data/create-conn router/schema)
        _       (data/transact! network dt)
        src     (rand-nth (alg/nodes @network))
        dst     (rand-nth (alg/nodes @network))
        router  (kt/->PedestrianRouter @network)]
    (timbre/info "\n\nDIJKSTRA forward with:" (count (alg/nodes @network)) "nodes")
    (timbre/info "**random graph")
    (c/quick-bench
      (let [coll (alg/dijkstra router #{src})]
        (alg/shortest-path dst coll))
      :os :runtime :verbose)))

(def network (delay (time (preprocessor/fetch-osm! {:area/name "Frankfurt am Main"}))))

;;(type @network) ;; force read

(test/deftest ^:benchmark dijkstra-saarland-graph
  (let [src  (first (alg/nodes @network))
        dst  (last (alg/nodes @network))
        router (kt/->PedestrianRouter @network)
        r1   (alg/looners @network router)
        router (kt/->PedestrianRouter @network)
        coll (alg/dijkstra router #{src})]
    (timbre/info "\n\nDIJKSTRA forward with:" (count (alg/nodes @network)) "nodes")
    (timbre/info "saarland graph:")
    (c/quick-bench (alg/shortest-path dst coll)
      :os :runtime :verbose)
    (timbre/info "--------")
    (timbre/info "using only strongly connected components of the original graph")
    (data/transact! @network (map #(vector :db.fn/retractEntity (:db/id %)) r1))
    (timbre/info "with:" (count (alg/nodes @network)) "nodes")
    (let [coll (alg/dijkstra router #{src})]
      (c/quick-bench (alg/shortest-path dst coll)
        :os :runtime :verbose))))

;; note src nil search will search for points greater or equal to src
;; I think nil src then search points less than src
(test/deftest ^:benchmark nearest-neighbour-search
  (let [src   [7.038535 49.345088]
        point (:node/location (first (fastq/nearest-node @network src)))]
    (timbre/info "\n\nsaarland graph: nearest neighbour search with random src/dst")
    (timbre/info "B+ tree with:" (count (data/datoms @network :eavt)) "nodes")
    (timbre/info "accuraccy: " (geometry/haversine src point) "meters")
    (c/quick-bench (:node/location (first (fastq/nearest-node @network src)))
                   :os :runtime :verbose)))
