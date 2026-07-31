(ns kotobase.protocols.sparql.parser-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest order-by-desc-is-carried-not-warned-about
  ;; This test used to assert a WARNING was produced, which pinned the bug as
  ;; the contract: DESC parsed, emitted "sorts ascending", and the warning was
  ;; then dropped by the HTTP handler so no client ever saw it. DESC now works,
  ;; so there is nothing to warn about.
  (let [parsed (parser/parse "SELECT ?n WHERE { ?u <urn:kotobase:name> ?n } ORDER BY DESC(?n)")
        ob (loop [n (:algebra parsed)] (if (= :order-by (:sparql/op n)) n (recur (:pattern n))))]
    (is (empty? (:warnings parsed)))
    (is (= '#{?n} (:desc ob)))))

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

;; --- ORDER BY DESC ---------------------------------------------------------

(deftest desc-reaches-the-algebra
  (testing "DESC was accepted and then discarded, leaving the algebra to sort
            ascending — the parser now carries the direction through"
    (let [{:keys [algebra]} (parser/parse "SELECT ?n WHERE { ?s <p> ?n } ORDER BY DESC(?n)")
          ob (loop [n algebra] (if (= :order-by (:sparql/op n)) n (recur (:pattern n))))]
      (is (= '[?n] (:vars ob)))
      (is (= '#{?n} (:desc ob))))))

(deftest asc-emits-no-desc-key
  (testing "backward compatible: an all-ascending ORDER BY is the same node it
            always was, with no :desc at all"
    (doseq [q ["SELECT ?n WHERE { ?s <p> ?n } ORDER BY ?n"
               "SELECT ?n WHERE { ?s <p> ?n } ORDER BY ASC(?n)"]]
      (let [{:keys [algebra]} (parser/parse q)
            ob (loop [n algebra] (if (= :order-by (:sparql/op n)) n (recur (:pattern n))))]
        (is (= '[?n] (:vars ob)))
        (is (not (contains? ob :desc)) q)))))

(deftest mixed-directions-are-per-var
  (let [{:keys [algebra]} (parser/parse "SELECT ?a ?b WHERE { ?a <p> ?b } ORDER BY ?a DESC(?b)")
        ob (loop [n algebra] (if (= :order-by (:sparql/op n)) n (recur (:pattern n))))]
    (is (= '[?a ?b] (:vars ob)))
    (is (= '#{?b} (:desc ob)) "only the var DESC() wrapped")))

(deftest no-warning-is-emitted-for-desc-anymore
  (testing "the warning existed because the feature did not work; it was also
            never surfaced to the client, which is what made it silent"
    (is (empty? (:warnings (parser/parse "SELECT ?n WHERE { ?s <p> ?n } ORDER BY DESC(?n)"))))))

;; --- FILTER || -------------------------------------------------------------

(defn- pred-of [algebra]
  (loop [n algebra] (if (= :filter (:sparql/op n)) (:pred n) (recur (:pattern n)))))

(defn- lit [v] (quads/->literal v))

(deftest filter-or-is-a-disjunction
  (let [pr (pred-of (p "SELECT ?s WHERE { ?s <urn:x> ?o . FILTER(?o = 1 || ?o = 2) }"))]
    (is (true? (pr {'?o (lit 1)})))
    (is (true? (pr {'?o (lit 2)})))
    (is (false? (pr {'?o (lit 3)})))))

(deftest or-binds-looser-than-and
  (testing "SPARQL 1.1 puts ConditionalOr over ConditionalAnd, so
            `a || b && c` is `a || (b && c)`. Parsing both at one level would
            make it `(a || b) && c` — a wrong answer, not a rejected query"
    (let [pr (pred-of (p "SELECT ?s WHERE { ?s <urn:x> ?o . FILTER(?a = 1 || ?b = 2 && ?c = 3) }"))]
      (is (true? (pr {'?a (lit 1) '?b (lit 0) '?c (lit 0)})) "left alternative alone suffices")
      (is (true? (pr {'?a (lit 0) '?b (lit 2) '?c (lit 3)})) "both halves of the and-chain")
      (is (false? (pr {'?a (lit 0) '?b (lit 2) '?c (lit 0)})) "half the and-chain is not enough")
      (is (false? (pr {'?a (lit 0) '?b (lit 0) '?c (lit 3)}))))))

(deftest or-composes-with-negation-and-bound
  (let [pr (pred-of (p "SELECT ?s WHERE { ?s <urn:x> ?o . FILTER(!(?o = 1) || BOUND(?z)) }"))]
    (is (true? (pr {'?o (lit 2)})))
    (is (false? (pr {'?o (lit 1)})))
    (is (true? (pr {'?o (lit 1) '?z (lit "x")})) "BOUND rescues it")))

(deftest and-alone-still-behaves
  (let [pr (pred-of (p "SELECT ?s WHERE { ?s <urn:x> ?o . FILTER(?a = 1 && ?b = 2) }"))]
    (is (true? (pr {'?a (lit 1) '?b (lit 2)})))
    (is (false? (pr {'?a (lit 1) '?b (lit 9)})))))
