(ns kotobase.protocols.sparql.quads
  "Turns an already-`kotobase.query.bridge/materialize`d `arrangement.core`
  db into a seq of `kotoba-lang/sparql`-shaped RDF quads (`{:subject
  :predicate :object}`, each position an `rdf.core`-style term map).

  ## Why we take the whole plane instead of calling `bridge/q`

  `kotoba-lang/sparql` (this protocol repo's query engine) has its own
  complete algebra -- BGP/join/filter/union/optional/project/distinct/
  order-by/slice -- and expects a plain in-memory seq of quads, not a
  `:find`/`:where` Datalog query. Routing every SPARQL query through
  `arrangement.datalog/q` first would mean translating SPARQL algebra INTO
  Datalog algebra and back, for no benefit.

  **This is the SUPPORTED path, not a deviation from one** (ADR-2608039970 in
  `com-junkawasaki/root`). What the query surfaces share is the datom plane
  -- `bridge/materialize` -- not Datalog: `q` is one frontend over that
  plane and this repo is another, and they are peers. The ADR was written
  from this repo's behaviour, which had been correct and undocumented since
  it landed; what was missing was anything saying so.

  So: `materialize` once, take every triple via `bridge/datoms`, hand
  `sparql.core` a plain seq.

  This namespace used to walk `(:spo db)` itself, because the bridge exposed
  no full-plane scan -- a raw index read, with nothing on the supported path
  to apply `visible?` for it. `bridge/datoms` is that scan
  (kotoba-lang/kotobase-query#7), and using it puts the predicate back where
  the plane is read rather than one layer above it.

  ## `visible?` -- required, not defaulted

  `visible?` is REQUIRED on every path into the plane (ADR-2607050500 --
  \"query as first-class effect\", no permissive default), and that now
  includes the scan itself: `bridge/datoms` refuses a missing or
  non-callable predicate before reading anything. `datoms->quads` keeps its
  own check as well -- it is this repo's error, with this repo's message, at
  this repo's boundary, and the two agree rather than one standing in for
  the other. `visible?` is `(fn [{:keys [s p o]}]) -> bool`, applied
  triple-by-triple BEFORE the term transform. Passing `(constantly true)` is
  a caller's explicit choice, never this namespace's default."
  (:require [clojure.string :as str]
            [kotobase.query.bridge :as bridge]))

;; --------------------------------------------------------------- IRI terms

(defn kw->iri-string
  "A materialized entity or attribute keyword -> a `urn:kotobase:...` IRI
  string. `:users/u1` -> `\"urn:kotobase:users/u1\"`; a bare (no-namespace)
  keyword like `:role` -> `\"urn:kotobase:role\"`. Deterministic and
  collision-free across every materialized collection (mirrors
  `kotobase.query.bridge`'s own entity-id scheme: `(keyword coll k)`), so
  the same source keyword always round-trips to the same IRI term, which is
  what BGP-pattern equality (`sparql.core/term=` is plain `=`) depends on.

  Total over what a datom attribute can actually be, not just over what it
  should be. `kotobase.query.bridge` now normalises document keys to keywords,
  which is the real fix for the case that found this — but before it did, a
  JSON-authored document put a STRING here, ClojureScript's `namespace` threw
  `Doesn't support namespace: <x>`, and every SPARQL query against that graph
  failed with an internal error whatever the query said (production,
  2026-07-31). A query surface should degrade to a usable IRI rather than 500
  when it meets an identifier shape it did not expect, so a non-`INamed`
  attribute is stringified rather than thrown on.

  A string `\"v2\"` and the keyword `:v2` therefore produce the SAME IRI. That
  is a deliberate conflation of two things the store can distinguish, and it is
  the right trade here only because the bridge no longer emits the string form;
  if it ever does again, the two collapse instead of erroring, and this comment
  is the warning."
  [kw]
  (if (or (keyword? kw) (symbol? kw))
    (str "urn:kotobase:" (if-let [ns (namespace kw)] (str ns "/" (name kw)) (name kw)))
    (str "urn:kotobase:" kw)))

(defn iri-string->kw
  "Inverse of `kw->iri-string`, for callers that need to go back from a
  bound `?var`'s IRI value to the originating keyword (e.g. to look up
  `:kotobase/coll`/`:kotobase/key` for a matched entity). Only handles the
  `urn:kotobase:...` scheme this namespace emits -- returns nil for
  anything else."
  [s]
  (when (str/starts-with? s "urn:kotobase:")
    (let [rest' (subs s (count "urn:kotobase:"))]
      (if-let [i (str/index-of rest' "/")]
        (keyword (subs rest' 0 i) (subs rest' (inc i)))
        (keyword rest')))))

(defn iri [v] {:rdf/type :iri :value v})

(defn ->literal
  "Plain literal term for `v` -- `{:rdf/type :literal :value v}`, `v`
  UNCOERCED (kept as the real Clojure/js value: string/number/boolean).
  Deliberately just these two keys, no `:datatype`/`:lang` -- see the ns
  docstring's equality note: `kotobase.protocols.sparql.parser` parses
  query-text literals into this exact same two-key shape so a query-side
  `900000` and a materialized doc's `:budget 900000` compare `=` (the
  datatype/lang tag SPARQL results output wants is derived from `v`'s
  runtime type at output time by `kotobase.protocols.sparql.results`, not
  carried on the term)."
  [v] {:rdf/type :literal :value v})

(defn ->term
  "One materialized `:o` value -> a SPARQL term. `:kotobase/coll`/
  `:kotobase/key` synthetic attrs and doc-map keyword attrs alike become
  plain literals here EXCEPT the special case below: `kotobase.query.
  bridge` stores a document's own keyword-valued reference fields (e.g.
  `:dept-key \"d1\"`) as plain strings, not as entity keywords -- so a
  cross-collection join (see `kotobase-query`'s own worked example) is
  written in SPARQL the same way it's written in Datalog: joining a
  literal `?dk` against the target's `:kotobase/key` LITERAL, not against
  an IRI. `:o` is therefore always a literal here; entities only ever
  appear as `:s` (via `->subject-term`)."
  [o] (->literal o))

(defn ->subject-term
  "A materialized `:s` (always a keyword entity id, `kotobase.query.
  bridge/materialize`'s `entity-id`) -> an IRI term."
  [s] (iri (kw->iri-string s)))

(defn ->predicate-term
  "A materialized `:p` (a keyword attr, doc-native or one of the two
  `:kotobase/*` synthetic attrs) -> an IRI term."
  [p] (iri (kw->iri-string p)))

;; ------------------------------------------------------------- db -> quads

(defn datoms->quads
  "`db` (the return of `kotobase.query.bridge/materialize`) -> a vector of
  `kotoba-lang/sparql`-shaped RDF quads (`{:subject :predicate :object}`
  term maps), ready to hand to `sparql.core/select`/`sparql.core/ask`.

  `visible?` is REQUIRED -- `(fn [{:keys [s p o]}]) -> bool`, the SAME
  triple-map shape `kotobase.query.bridge`'s access paths use, applied to
  every `{:s :p :o}` BEFORE the term transform. `bridge/datoms` applies it
  to the scan; the check below is this repo's own, at this repo's boundary,
  so a caller gets this repo's error rather than one from underneath it.
  Pass `(constantly true)` to see everything materialized; that is a
  caller's explicit choice, never this fn's default (there is no 3-arity
  that omits it)."
  [db visible?]
  (when-not (ifn? visible?)
    (throw (ex-info "datoms->quads: visible? is required (no permissive default, ADR-2607050500)"
                     {:type ::visible?-required})))
  (into []
        (map (fn [{:keys [s p o]}]
               {:subject (->subject-term s)
                :predicate (->predicate-term p)
                :object (->term o)}))
        (bridge/datoms db visible?)))
