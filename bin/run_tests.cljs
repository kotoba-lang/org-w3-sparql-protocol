;; nbb test runner -- first-class runtime per repo rule (kotoba wasm >
;; clojurewasm > cljs > nbb > (jvm/bb)). Run from the repo root:
;;
;;   nbb --classpath "src:test:.deps/sparql/src:.deps/kotobase-query/src:.deps/kotobase/src:.deps/arrangement/src:.deps/prolly-tree/src:.deps/io-ipld/src:.deps/io-multiformats/src:.deps/org-ietf-cbor/src" bin/run_tests.cljs
;;
;; where every .deps/<name> is a checkout of the matching kotoba-lang repo
;; at the SHA pinned in deps.edn (sparql, kotobase-query) or transitively
;; pinned by kotobase-query's own deps.edn / README (kotobase, arrangement,
;; prolly-tree, io-ipld, io-multiformats, org-ietf-cbor -- kotobase-query
;; needs the whole arrangement dependency chain at namespace-load time even
;; though this repo, like kotobase-query itself, never calls arrangement.
;; core/commit!). CI pins every one of them to the same SHAs -- see
;; .github/workflows/ci.yml. `npm install` this repo's package.json first
;; (transitive @noble/hashes, see README).
(ns run-tests
  (:require [cljs.test :as t]
            [kotobase.protocols.sparql.quads-test]
            [kotobase.protocols.sparql.parser-test]
            [kotobase.protocols.sparql.results-test]
            [kotobase.protocols.sparql-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (set! (.-exitCode js/process) 1)))

(t/run-tests 'kotobase.protocols.sparql.quads-test
             'kotobase.protocols.sparql.parser-test
             'kotobase.protocols.sparql.results-test
             'kotobase.protocols.sparql-test)
