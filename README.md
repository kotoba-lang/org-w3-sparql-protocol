# org-w3-sparql-protocol

[![CI](https://github.com/kotoba-lang/org-w3-sparql-protocol/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/org-w3-sparql-protocol/actions/workflows/ci.yml)

`sparql.<apex>` — the [SPARQL 1.1 Protocol](https://www.w3.org/TR/sparql11-protocol/)
HTTP wire surface for kotobase, wrapping [`kotoba-lang/sparql`](https://github.com/kotoba-lang/sparql)'s
already-real EDN algebra around data materialized by
[`kotoba-lang/kotobase-query`](https://github.com/kotoba-lang/kotobase-query)'s bridge.
This is the SPARQL row of ADR-2607172300 (`com-junkawasaki/root`, "kotobase
query-protocol extension"), the most tractable of four sibling query-protocol
repos in that batch (`datomic-client-shim`, `org-postgresql-wire`,
`org-opencypher-cypher`, `org-w3-sparql-protocol`) — most of the query engine
this repo needs already existed before it was written.

**Naming note**: the bare name `sparql` is already `kotoba-lang/sparql` (the
algebra library this repo depends on). SPARQL 1.1 Protocol is a W3C spec, so
this repo follows the org's existing `org-w3-*` reverse-domain convention
(`org-w3-webgpu`, `org-w3-did`).

## What this repo is (and isn't)

- **Is**: an HTTP transport (`GET`/`POST` + `Accept` negotiation) around
  `kotoba-lang/sparql`'s existing `select`/`ask` — plus the one piece that
  had to be built new: SPARQL QUERY-TEXT parsing (`kotoba-lang/sparql`
  intentionally ships no text-syntax parser, see its README — "a separate
  parser repo is the natural place for `SELECT ?s WHERE {...}` string
  syntax"). `kotobase.protocols.sparql.parser` is that parser, scoped
  strictly to translate text into the algebra `kotoba-lang/sparql` already
  implements — it adds **zero new query semantics**.
- **Isn't**: a new SPARQL engine, a full SPARQL 1.1 grammar implementation,
  or a place that extends `kotoba-lang/sparql`'s algebra. Every algebra node
  the parser ever emits is one of the 8 `:sparql/op` shapes that repo's
  README documents.

## The integration decision: `materialize` + our own quad transform, NOT `bridge/q`

`kotobase-query`'s bridge offers two things: `materialize` (`IStore` +
collection keys → an `arrangement.core` db) and `q`/`query` (run a
Datomic-shaped `:find`/`:where` query over that db via `arrangement.datalog`).
**This repo uses `materialize` only.** `kotoba-lang/sparql` already has its
own complete algebra (BGP/join/filter/union/optional/project/distinct/
order-by/slice) over a plain in-memory quad seq — routing every SPARQL query
through `arrangement.datalog` first would mean translating SPARQL algebra
INTO Datalog algebra and back for no benefit. Instead:
`kotobase.protocols.sparql.quads/datoms->quads` walks the materialized db's
`:spo` index directly (the exact same fully-unbound-scan shape
`arrangement.query`'s own `:else` branch produces) and transforms each
`{:s :p :o}` triple into an `rdf.core`-shaped RDF quad
(`{:subject :predicate :object}`, term maps `{:rdf/type :iri|:literal :value
...}`) ready for `sparql.core/select`/`sparql.core/ask`.

**Doc-map keys and materialized entities become IRIs** (`kw->iri-string`:
`:users/u1` → `urn:kotobase:users/u1`, `:role` → `urn:kotobase:role`) so BGP
patterns can match them by structural equality. **Object-position values are
always literals**, never IRIs — this deliberately mirrors
`kotobase-query`'s own worked cross-collection-join example (a foreign-key
field like `:dept-key "d1"` is a plain string that joins against the target
entity's `:kotobase/key` literal, not an IRI reference) — see
`kotobase.protocols.sparql.quads`'s ns docstring for the full reasoning.

### `visible?` — required, applied in our own quad transform, not via `bridge/q`

Every `kotobase-query`/`arrangement.datalog` query function requires an
explicit `visible?` predicate (ADR-2607050500, "query as first-class
effect" — no permissive default). Because this repo bypasses `bridge/q`
entirely (see above), that enforcement point never fires for us. So
`datoms->quads` re-implements the identical discipline at the one place
redaction can actually happen here: it takes `visible?` as a **required**
argument (`(fn [{:keys [s p o]}]) -> bool`, the exact same triple-map shape
`arrangement.query`/`bridge/q` already use) and applies it as a post-filter
over every candidate triple **before** it becomes a queryable RDF quad.
`(constantly true)` is a caller's explicit choice, never this repo's
default — there is no arity that omits it.

## Implemented subset (v0.1)

- `GET /sparql?query=<SPARQL text>` and `POST /sparql`.
  - **Primary POST form**: `Content-Type: application/sparql-query` — the
    entire body IS the query text, zero decoding ambiguity.
  - **Secondary POST form**: `application/x-www-form-urlencoded` with a
    `query` field — supported, with a real (UTF-8-correct, not byte-by-byte)
    percent-decoder.
  - GET's `:query` map is assumed already percent-decoded by the caller/
    deploy shell, matching `kotobase-protocols`' own `:query`/`http/
    query-param` convention.
- `Accept: application/sparql-results+json` (or `*/*`/absent, which default
  to JSON) — [SPARQL 1.1 Query Results JSON Format](https://www.w3.org/TR/sparql11-results-json/)
  for both `SELECT` and `ASK`. Anything else gets `406 Not Acceptable` —
  **XML and CSV results formats are explicitly not implemented in v0.1**
  (per the task scope: "nice-to-have, not required").
- Query language subset: exactly what `kotoba-lang/sparql`'s algebra
  supports — `PREFIX` declarations, `SELECT`(`DISTINCT`)/`ASK`, `WHERE {
  ... }` with triple patterns / `OPTIONAL` / `{ } UNION { }` / `FILTER`
  (`=` `!=` `<` `>` `<=` `>=`, `&&`, unary `!`, `BOUND(?v)`), `ORDER BY`,
  `LIMIT`/`OFFSET`. See `kotobase.protocols.sparql.parser`'s ns docstring
  for the precise grammar and its explicit non-goals (no property paths /
  `GRAPH` / `SERVICE` / aggregates / subqueries / predicate-object lists —
  `kotoba-lang/sparql`'s algebra has none of these operators to translate
  into, so this parser doesn't either).
- `CONSTRUCT`/`DESCRIBE`/`UPDATE` are not implemented — no RDF-graph-out or
  write path in `kotoba-lang/sparql`'s algebra to wrap.

## Usage

```clojure
(require '[kotobase.local :as local]
         '[kotobase.store :as st]
         '[kotobase.protocols.sparql :as sparql-protocol])

(def store (local/local-store))
(st/-put store "users" "u1" {:name "Alice" :role "admin" :dept-key "d1"})
(st/-put store "departments" "d1" {:name "Engineering"})

(def ctx {:store store
          :coll-keys ["users" "departments"]
          :visible? (constantly true)}) ; caller's explicit choice, see above

(sparql-protocol/handle
 ctx
 {:method :get
  :query {"query" "SELECT ?uname ?dname WHERE {
                     ?u <urn:kotobase:name> ?uname .
                     ?u <urn:kotobase:dept-key> ?dk .
                     ?d <urn:kotobase:kotobase/coll> \"departments\" .
                     ?d <urn:kotobase:kotobase/key> ?dk .
                     ?d <urn:kotobase:name> ?dname . }"}
  :headers {"accept" "application/sparql-results+json"}})
;;=> {:status 200
;;    :headers {"content-type" "application/sparql-results+json"}
;;    :body "{\"head\":{\"vars\":[\"uname\",\"dname\"]},\"results\":{\"bindings\":[{\"uname\":{\"type\":\"literal\",\"value\":\"Alice\"},\"dname\":{\"type\":\"literal\",\"value\":\"Engineering\"}}]}}"}
```

`:coll-keys` is the fixed set of `kotobase.store` collections this endpoint
exposes as its RDF dataset — there is no per-request dataset selection
(SPARQL's own `default-graph-uri`/`named-graph-uri` protocol params are out
of scope, matching `kotoba-lang/sparql`'s own no-named-graphs boundary).

## Scope guards (read before extending)

- **v0.1 re-materializes on every request** — no caching, same linear-scan
  limitation `kotobase.query.bridge/materialize` itself documents. This
  handler adds no caching on top of it. Real incremental indexing is a
  follow-up once there is real query-volume evidence, not a v0.1
  requirement (ADR-2607172300).
- **`FILTER`'s `=`/`!=`/`<`/`>`/`<=`/`>=` compare terms' `:value` only** —
  an IRI and a literal that happen to share the same string `:value` would
  compare equal. Given this repo's own quad transform never emits an
  object-position IRI, this does not arise in practice for BGP-produced
  bindings, but is stated here rather than left implicit.
- **`ORDER BY DESC(...)` is accepted syntactically but sorts ascending** —
  `sparql.core`'s `:order-by` node has no direction flag. `parser/parse`
  surfaces this in a `:warnings` vector rather than silently reversing
  nothing, so a caller can choose to surface it.
- **No predicate-object lists** (`s p o1 , o2`) or **property lists**
  (`s p1 o1 ; p2 o2`) — one `s p o .` triple per statement.
- **`kotobase.protocols.sparql.json`** is a small vendored copy of
  `kotobase-protocols`' `json.cljc` `encode` pattern (encode-only — nothing
  in this protocol's request path is JSON to parse), not a code dependency
  on that repo. ADR-2607172300's dependency table for this repo lists
  exactly `kotoba-lang/sparql` + `kotoba-lang/kotobase-query`;
  `kotobase-protocols` is this effort's wire-protocol-handler STYLE
  reference, not a dependency of this repo.

## Dependencies

- [`kotoba-lang/sparql`](https://github.com/kotoba-lang/sparql) — the SPARQL
  algebra (`sparql.core/select`, `sparql.core/ask`) this repo wraps, as-is.
- [`kotoba-lang/kotobase-query`](https://github.com/kotoba-lang/kotobase-query) —
  `kotobase.query.bridge/materialize`, the `IStore` → `arrangement.core` db
  bridge this repo's own `kotobase.protocols.sparql.quads` walks.
  Transitively pulls in `kotoba-lang/kotobase` (`IStore`/`LocalStore`),
  `kotoba-lang/arrangement` (the 4-covering index + its `commit!`/CID-
  snapshot machinery, unused by this repo but required at namespace-load
  time), and in turn `prolly-tree`/`io-ipld`/`io-multiformats`/
  `org-ietf-cbor` — see `kotobase-query`'s own README for why. `deps.edn`'s
  two direct git deps resolve that whole chain automatically for the JVM
  `:test` alias via `tools.deps`; the nbb primary test path has no
  dependency resolver, so `bin/run_tests.cljs`/CI clone every transitive dep
  by hand.
- npm `@noble/hashes` — transitive JS-runtime dep of `io-multiformats`,
  same as `kotobase-query`'s own `package.json`.

## Develop / test

First-class runtime is **nbb/cljs** (repo-wide runtime priority):

```bash
git clone https://github.com/kotoba-lang/sparql .deps/sparql
git clone https://github.com/kotoba-lang/kotobase-query .deps/kotobase-query
git clone https://github.com/kotoba-lang/kotobase .deps/kotobase
git clone https://github.com/kotoba-lang/arrangement .deps/arrangement
git clone https://github.com/kotoba-lang/prolly-tree .deps/prolly-tree
git clone https://github.com/kotoba-lang/io-ipld .deps/io-ipld
git clone https://github.com/kotoba-lang/io-multiformats .deps/io-multiformats
git clone https://github.com/kotoba-lang/org-ietf-cbor .deps/org-ietf-cbor
npm install
nbb --classpath "src:test:.deps/sparql/src:.deps/kotobase-query/src:.deps/kotobase/src:.deps/arrangement/src:.deps/prolly-tree/src:.deps/io-ipld/src:.deps/io-multiformats/src:.deps/org-ietf-cbor/src" bin/run_tests.cljs
```

Each `.deps/<name>` should be checked out at the SHA pinned in `deps.edn`
(`sparql`, `kotobase-query`) or transitively (the rest) — CI pins every one
of them, see `.github/workflows/ci.yml`.

The `:test` alias in `deps.edn` is the JVM **compat** suite only (`clojure
-M:test`, via `tools.deps` transitive git-dep resolution — no manual
`.deps/` cloning needed for this path) — not the primary execution path.
`clojure -M:lint` runs `clj-kondo`.

## License

Apache-2.0
