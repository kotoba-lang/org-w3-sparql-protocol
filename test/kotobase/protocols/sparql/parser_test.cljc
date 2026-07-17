(ns kotobase.protocols.sparql.parser-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.protocols.sparql.parser :as parser]
            [kotobase.protocols.sparql.quads :as quads]))

(defn- p [text] (:algebra (parser/parse text)))

(deftest select-star-bgp
  (let [parsed (parser/parse "SELECT * WHERE { ?s <urn:kotobase:role> \"admin\" }")]
    (is (= :select (:form parsed)))
    (is (nil? (:output-vars parsed)))
    (is (= {:sparql/op :bgp :patterns [['?s (quads/iri "urn:kotobase:role") (quads/->literal "admin")]]}
           (:algebra parsed)))))

(deftest select-projects-listed-vars
  (let [parsed (parser/parse "SELECT ?s ?r WHERE { ?s <urn:kotobase:role> ?r }")]
    (is (= '[?s ?r] (:output-vars parsed)))
    (is (= :project (:sparql/op (:algebra parsed))))))

(deftest multi-triple-bgp-joins-on-shared-var
  (let [algebra (p "SELECT ?n WHERE { ?u <urn:kotobase:role> \"admin\" . ?u <urn:kotobase:name> ?n . }")]
    (is (= :project (:sparql/op algebra)))
    (is (= :bgp (:sparql/op (:pattern algebra))))
    (is (= 2 (count (:patterns (:pattern algebra)))))))

(deftest filter-equality-and-inequality
  (let [algebra (p "SELECT ?n WHERE { ?u <urn:kotobase:name> ?n . FILTER(?n != \"Bob\") }")
        pred-node (:pattern algebra)]
    (is (= :filter (:sparql/op pred-node)))
    (is (ifn? (:pred pred-node)))
    (is (true? ((:pred pred-node) {'?n (quads/->literal "Alice")})))
    (is (false? ((:pred pred-node) {'?n (quads/->literal "Bob")})))))

(deftest filter-numeric-comparison
  (let [algebra (p "SELECT ?b WHERE { ?d <urn:kotobase:budget> ?b . FILTER(?b > 500000) }")
        pred-node (:pattern algebra)]
    (is (true? ((:pred pred-node) {'?b (quads/->literal 900000)})))
    (is (false? ((:pred pred-node) {'?b (quads/->literal 400000)})))))

(deftest filter-conjunction
  (let [algebra (p "SELECT ?b WHERE { ?d <urn:kotobase:budget> ?b . FILTER(?b > 100000 && ?b < 1000000) }")
        pred-node (:pattern algebra)]
    (is (true? ((:pred pred-node) {'?b (quads/->literal 900000)})))
    (is (false? ((:pred pred-node) {'?b (quads/->literal 2000000)})))))

(deftest union-of-two-blocks
  (let [algebra (p "SELECT ?s WHERE { { ?s <urn:kotobase:role> \"admin\" } UNION { ?s <urn:kotobase:role> \"user\" } }")]
    (is (= :union (:sparql/op (:pattern algebra))))))

(deftest optional-wraps-left-join
  (let [algebra (p "SELECT ?s ?f WHERE { ?s <urn:kotobase:name> ?n . OPTIONAL { ?s <urn:kotobase:knows> ?f } }")]
    (is (= :optional (:sparql/op (:pattern algebra))))))

(deftest distinct-and-slice
  (let [parsed (parser/parse "SELECT DISTINCT ?r WHERE { ?u <urn:kotobase:role> ?r } LIMIT 1 OFFSET 1")
        algebra (:algebra parsed)]
    (is (= :slice (:sparql/op algebra)))
    (is (= 1 (:limit algebra)))
    (is (= 1 (:offset algebra)))
    (is (= :distinct (:sparql/op (:pattern algebra))))))

(deftest order-by
  (let [parsed (parser/parse "SELECT ?n WHERE { ?u <urn:kotobase:name> ?n } ORDER BY ?n")
        algebra (:algebra parsed)]
    ;; :project wraps :order-by
    (is (= :project (:sparql/op algebra)))
    (is (= :order-by (:sparql/op (:pattern algebra))))
    (is (= '[?n] (:vars (:pattern algebra))))))

(deftest order-by-desc-warns-but-still-parses
  (let [parsed (parser/parse "SELECT ?n WHERE { ?u <urn:kotobase:name> ?n } ORDER BY DESC(?n)")]
    (is (seq (:warnings parsed)))))

(deftest ask-form
  (let [parsed (parser/parse "ASK WHERE { ?u <urn:kotobase:role> \"admin\" }")]
    (is (= :ask (:form parsed)))
    (is (nil? (:output-vars parsed)))
    (is (= :bgp (:sparql/op (:algebra parsed))))))

(deftest prefix-declarations-resolve-pname
  (let [algebra (p "PREFIX ex: <urn:kotobase:> SELECT ?s WHERE { ?s ex:role \"admin\" }")]
    (is (= (quads/iri "urn:kotobase:role")
           (second (first (:patterns (:pattern algebra))))))))

(deftest bound-builtin
  (let [algebra (p "SELECT ?s WHERE { ?s <urn:kotobase:name> ?n . FILTER(BOUND(?n)) }")
        pred-node (:pattern algebra)]
    (is (true? ((:pred pred-node) {'?n (quads/->literal "Alice")})))
    (is (false? ((:pred pred-node) {})))))

(deftest malformed-query-throws
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (parser/parse "SELECT ?s WHERE { ?s"))))

(deftest unsupported-form-throws
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (parser/parse "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"))))
