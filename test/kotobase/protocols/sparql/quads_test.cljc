(ns kotobase.protocols.sparql.quads-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.local :as local]
            [kotobase.store :as st]
            [kotobase.query.bridge :as bridge]
            [kotobase.protocols.sparql.quads :as quads]))

(defn- fixture-store []
  (let [s (local/local-store)]
    (st/-put s "users" "u1" {:name "Alice" :role "admin" :dept-key "d1"})
    (st/-put s "departments" "d1" {:name "Engineering"})
    s))

(deftest kw->iri-string-roundtrips
  (is (= "urn:kotobase:users/u1" (quads/kw->iri-string :users/u1)))
  (is (= "urn:kotobase:role" (quads/kw->iri-string :role)))
  (is (= :users/u1 (quads/iri-string->kw "urn:kotobase:users/u1")))
  (is (= :role (quads/iri-string->kw "urn:kotobase:role")))
  (is (nil? (quads/iri-string->kw "http://example.org/x"))))

(deftest datoms->quads-transforms-every-triple
  (let [db (bridge/materialize (fixture-store) ["users" "departments"])
        qs (quads/datoms->quads db (constantly true))]
    (testing "every quad is subject=IRI, predicate=IRI, object=literal"
      (is (seq qs))
      (doseq [{:keys [subject predicate object]} qs]
        (is (= :iri (:rdf/type subject)))
        (is (= :iri (:rdf/type predicate)))
        (is (= :literal (:rdf/type object)))))
    (testing "a specific doc attr survives the transform"
      (is (some #(and (= (quads/iri "urn:kotobase:users/u1") (:subject %))
                       (= (quads/iri "urn:kotobase:name") (:predicate %))
                       (= (quads/->literal "Alice") (:object %)))
                qs)))
    (testing "synthetic :kotobase/coll and :kotobase/key are present"
      (is (some #(= (quads/iri "urn:kotobase:kotobase/coll") (:predicate %)) qs))
      (is (some #(= (quads/iri "urn:kotobase:kotobase/key") (:predicate %)) qs)))))

(deftest visible-filters-triples-before-quad-transform
  (let [db (bridge/materialize (fixture-store) ["users" "departments"])
        no-u1? (fn [{:keys [s]}] (not= s :users/u1))
        qs (quads/datoms->quads db no-u1?)]
    (is (not (some #(= (quads/iri "urn:kotobase:users/u1") (:subject %)) qs)))
    (is (some #(= (quads/iri "urn:kotobase:departments/d1") (:subject %)) qs))))

(deftest visible-is-required-not-defaulted
  (let [db (bridge/materialize (fixture-store) ["users"])]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (quads/datoms->quads db nil))
        "no permissive default -- ADR-2607050500")))

;; --- non-keyword attributes do not 500 the surface --------------------------
;; kotobase.query.bridge normalises document keys to keywords, which is the
;; real fix. This is the floor underneath it: production spent an unknown
;; period answering every SPARQL query with "internal error: Doesn't support
;; namespace: v2" — ClojureScript's `namespace` throwing on a string — while
;; /health returned 200. A query surface meeting an unexpected identifier
;; shape should produce a usable IRI, not an internal error.

(deftest a-string-attribute-produces-an-iri-rather-than-throwing
  (is (= "urn:kotobase:v2" (quads/kw->iri-string "v2")))
  (is (= "urn:kotobase:text" (quads/kw->iri-string "text"))))

(deftest keywords-are-unchanged
  (is (= "urn:kotobase:users/u1" (quads/kw->iri-string :users/u1)))
  (is (= "urn:kotobase:role" (quads/kw->iri-string :role))))

(deftest datoms-to-quads-survives-a-string-attribute
  ;; The exact shape that was failing in production, driven through the
  ;; function that actually transforms terms.
  (let [db {:spo {:c/k {"v2" #{1} :ok #{2}}}}
        qs (quads/datoms->quads db (constantly true))]
    (is (= 2 (count qs)))
    (is (= #{"urn:kotobase:v2" "urn:kotobase:ok"}
           (into #{} (map (comp :value :predicate)) qs)))))
