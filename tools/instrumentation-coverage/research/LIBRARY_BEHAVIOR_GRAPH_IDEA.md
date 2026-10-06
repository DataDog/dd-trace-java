# Library behavior graph: a reusable map for instrumentation coverage

Saved 2026-09-25. Local working document; no commit or publication.

## Idea

Build a versioned graph of a library's possible execution structure, then overlay documentation,
existing tests, runtime execution, Context evidence, and profiling. The graph becomes reusable
knowledge for instrumentation authors, coding agents, quality gates, and the instrumentation
portal.

This is a graph-theory problem with several evidence layers. It is not a claim that static analysis
can recover every framework flow or determine correct Context behavior by itself.

## Separate graphs and overlays

Preserve a lossless base graph and derive simpler views from it:

1. **Artifact graph**: classes, methods, inheritance, declared calls, field/type references, and
   external symbolic targets from the exact resolved artifacts.
2. **Possible-reachability graph**: virtual dispatch expansion and configured semantic edges for
   reflection, callbacks, executors, servlet redispatch, dependency injection, and other framework
   mechanisms.
3. **Behavior-flow graph**: named, versioned flows extracted from public documentation, examples,
   source, and tests, such as synchronous request, Callable success, DeferredResult error, or async
   timeout.
4. **Evidence overlay**: methods and edges observed by tests, profiles, context-lifecycle collection,
   and sanitized customer or escalation samples, attributed to concrete scenarios and environments
   where possible.
5. **Expectation overlay**: reviewed requirements for Context presence, lineage, restoration, spans,
   and other products at meaningful boundaries.

Static, documented, inferred, observed, and reviewed data must remain distinguishable. A generated
flow or method binding cannot silently become a quality gate.

## Spring WebMVC 6 prototype

Start from the actual artifacts used by the existing Spring WebMVC 6 test task. The current pinned
test environment resolves Spring Framework 6.0.2. The relevant Spring closure is:

- `spring-webmvc`
- `spring-web`
- `spring-context`
- `spring-beans`
- `spring-core`
- `spring-expression`
- `spring-aop`
- `spring-jcl`

External APIs such as Jakarta Servlet, Micrometer Observation, and reactive-streams should remain
as symbolic boundary nodes initially. Add their bytecode only when following a flow across that
boundary is useful. Do not treat the complete instrumentation-test runtime classpath as the
library: it includes the tracer, test harness, Spring Boot, Tomcat, security, assertion libraries,
and unrelated support code.

### Phase 1: lossless bytecode graph

Parse every class file without loading it. Give every node a stable version-specific identity:

```text
org.springframework:spring-webmvc:6.0.2
  #org.springframework.web.servlet.DispatcherServlet
  #doDispatch(Ljakarta/servlet/http/HttpServletRequest;Ljakarta/servlet/http/HttpServletResponse;)V
```

Record:

- artifact and hash;
- class, method name, and descriptor;
- access flags, annotations, synthetic/bridge status, source file, and line range when available;
- superclass and interfaces;
- bytecode call sites with opcode and source line;
- `invokedynamic` call-site identity, bootstrap method, bootstrap constants, and method-handle
  targets, including lambda bodies and method references exposed by `LambdaMetafactory`;
- references to targets outside the scanned artifact set.

The first graph should retain constructors, accessors, `toString`, generated bridges, lambda
bodies, and isolated methods. Simplification is a derived view, never destructive ingestion.

### Phase 2: deterministic simplification

Produce multiple reproducible views instead of one subjective cleaned graph:

- **Declared graph**: all methods and bytecode call edges.
- **Framework graph**: remove synthetic/bridge methods, trivial Object overrides, compiler accessors,
  and isolated nodes while recording the exact rule that removed each node.
- **Inter-class graph**: collapse methods to classes and weight an edge by the number of distinct
  call sites.
- **Package graph**: collapse classes to packages for the first overview.
- **Core graph**: retain the highest non-trivial k-core and report the k value.
- **Boundary graph**: retain paths touching public entry points, tracer matcher targets, async
  handoffs, servlet APIs, callbacks, or documented flow anchors.

Do not remove ordinary getters or setters only because of their names. Frameworks frequently use
them for lifecycle state. A method is noise only under an explicit structural rule, for example a
zero-dependency `toString` override whose only outgoing edges are `StringBuilder` operations.

### Phase 3: graph analysis

Calculate at least:

- in-degree and out-degree;
- weighted class/package degree;
- weakly and strongly connected components;
- k-core/coreness;
- PageRank or eigenvector-style centrality;
- approximate betweenness for candidate bridges;
- articulation points and bridges in useful undirected projections;
- shortest paths between documented entry, handoff, resume, error, and completion anchors;
- communities, initially using deterministic label propagation or another recorded algorithm.

High connectivity does not imply instrumentation importance. Dense regions can be configuration,
conversion, or utility machinery. Centrality is a navigation and prioritization signal that must be
combined with boundary relevance, documented behavior, runtime evidence, and tracer targets.

### Phase 4: documentation-backed flows

Represent documentation as cited claims and flow templates. For Spring MVC asynchronous Callable
handling, a draft flow is:

```text
HTTP request
  -> DispatcherServlet dispatch
  -> handler resolution
  -> controller invocation
  -> controller returns Callable
  -> WebAsyncManager.startCallableProcessing
  -> AsyncTaskExecutor handoff
  -> Callable.call
  -> concurrent result stored
  -> servlet async redispatch
  -> concurrent result consumed
  -> response handling/rendering
```

Documentation establishes that the behavior is supported and describes semantic transitions.
Static analysis binds steps to candidate methods. Runtime evidence confirms which concrete path an
existing scenario took. Human review is required only for semantic importance and product
expectations, not for every bytecode node.

Each flow stores:

- stable flow ID, library, and supported version range;
- entry conditions and observable outcome;
- ordered or partially ordered steps;
- success, failure, timeout, cancellation, and alternate implementation branches;
- method/class/package bindings with confidence and provenance;
- asynchronous and lifecycle boundaries;
- existing tests that exercise it;
- runtime evidence and last-seen versions;
- proposed and reviewed Context/product expectations;
- direct citations to documentation or source.

### Customer and escalation evidence

Customer tickets and escalations can enrich the flow map with execution that the tracer repository's
fixtures do not contain. A trace, profile, stack, diagnostic recording, or minimal reproduction can:

- confirm that a documented or inferred flow occurs in real applications;
- reveal a configured implementation, callback, proxy, or boundary missing from the static graph;
- add a new flow variant, failure mode, or library-version binding;
- show which structurally possible paths are operationally relevant;
- prioritize missing tests and instrumentation work;
- provide the basis for a sanitized regression scenario.

Treat this as evidence attached to a flow, step, node, or edge. It must not silently redefine the
flow or establish a universal expectation. One customer sample demonstrates existence under its
recorded environment; it does not establish prevalence, full reachability, or the correct Context
contract for every configuration.

Store derived, sanitized evidence in the reusable knowledge base rather than copying raw customer
payloads. A record should contain:

```yaml
id: customer-evidence-opaque-id
sourceKind: support-ticket
caseReference: secure-system-reference
accessClassification: internal-restricted
library:
  coordinate: org.springframework:spring-webmvc
  version: 6.0.2
environment:
  runtime: Java 17
  relevantConfiguration:
    asyncReturnFamily: CompletableFuture
flowBinding:
  flowId: spring-webmvc.async.completion-stage.error
  stepIds:
    - result-and-dispatch
    - redispatch-result-consumption
observations:
  methodFingerprints:
    - stable-method-id
  contextState: unknown
  symptom: trace-lineage-break
confidence: reproduced
artifacts:
  - secure-reference-to-original-sample
sanitization:
  rawPayloadStoredInKnowledgeBase: false
```

The case or artifact remains in its access-controlled system. The shared graph stores a stable
reference, normalized method/flow bindings, the minimum environment needed to interpret them, and
the sanitization status. Product identifiers, URLs, resource names, arguments, payloads, and other
customer data are excluded unless an approved workflow explicitly permits them.

A useful promotion path is:

```text
customer sample
  -> normalized graph evidence
  -> candidate flow or edge
  -> local sanitized reproduction
  -> instrumentation test
  -> reviewed expectation or quality gate
```

The source remains visible throughout. Reproduction can strengthen confidence, but should not erase
the customer evidence that explained why the flow became important.

### Phase 5: test and Context overlay

Join existing test execution to graph nodes and edges. Keep these states separate:

- possible statically;
- documented as part of a supported flow;
- exercised by an attributed test scenario;
- observed only at task level or by sampling;
- observed with non-root Context, root Context, or both;
- expected to carry a particular lineage;
- unobserved with complete collection;
- unknown because attribution or collection was incomplete.

This enables questions such as:

- Which documented flows have no exercising test?
- Which test covers a flow only incidentally?
- Where is the first observed transition from non-root to root Context?
- Which downstream region is reachable after that boundary?
- Which central or frequently sampled region is outside the current method inventory?
- Which library-version change invalidated a flow binding?

## Reusable knowledge model

Keep reviewable source records in version-controlled JSON or YAML and compile them into a property
graph, SQLite database, or portal index. Graph databases are a query and visualization layer, not
the only copy of the knowledge.

Useful node types include `Artifact`, `Type`, `Method`, `CallSite`, `Capability`, `Flow`, `FlowStep`,
`Boundary`, `Scenario`, `Test`, `Instrumentation`, `Expectation`, `Document`, `CustomerEvidence`,
`CaseReference`, and `Observation`.
Useful edge types include `DECLARES`, `CALLS`, `MAY_DISPATCH_TO`, `OVERRIDES`, `IMPLEMENTS`,
`HANDS_OFF_TO`, `REDISPATCHES_TO`, `DOCUMENTS`, `BINDS_TO`, `EXERCISES`, `OBSERVED_NEXT`,
`REPORTED_IN`, `REPRODUCES`, `INSTRUMENTED_BY`, and `EXPECTS_CONTEXT`.

Every semantic edge carries provenance, version range, confidence, and evidence. Contradictions are
preserved and surfaced rather than resolved silently.

## Prototype output

The first prototype should produce:

1. raw method-level JSON for Spring WebMVC 6.0.2 and its relevant Spring dependencies;
2. simplified method, class, and package graph JSON;
3. a Markdown analysis with graph sizes, removed-node reasons, components, k-core, and top central
   nodes;
4. a standalone interactive graph view with raw/core/boundary modes;
5. one documentation-backed async flow mapped onto the graph;
6. an overlay of the existing Spring context-coverage and profiling evidence;
7. an explicit list of static gaps, semantic edges, unresolved targets, and limitations.

Success is not a complete or perfectly precise Spring call graph. Success means the graph is
reproducible, retains its evidence, exposes a useful structural core, and can explain how a test or
Context observation relates to a documented library flow.

## First prototype result

The initial extractor now scans the eight-artifact Spring Framework closure resolved from
`spring-webmvc:6.0.2`. It produced:

- 30,706 declared methods;
- 95,525 bytecode invocation instructions;
- 4,459 `invokedynamic` call sites;
- 1,645 LambdaMetafactory call sites with implementation handles;
- 41,069 unique internal method edges;
- 22,080 methods after reversible synthetic, Object-override, and isolated-node filtering;
- a maximum method-level k-core of 7.

The unscoped centrality results are dominated by assertions, logging, string utilities, embedded
ASM/CGLIB, and bean configuration. This is useful negative evidence: density alone does not find the
most relevant instrumentation behavior. The next structural view should constrain paths around
documented flow anchors, tracer matcher targets, runtime-observed nodes, and execution boundaries.

Two documentation-backed draft flows—Callable success and DeferredResult success—bind to concrete
graph nodes. Direct bytecode paths establish only some transitions. Framework dispatch, callback,
application-producer, and redispatch transitions remain explicit `SEMANTIC_EDGE_REQUIRED` gaps.
Existing Context reports overlay method observations, but do not establish either end-to-end flow
because worker observations are not attributed to a request/test. In particular, observed
DeferredResult implementation methods may come from CompletableFuture adaptation and are not proof
of a dedicated DeferredResult scenario.

A fresh test-task join validates the intended separation. The knowledge bundle binds the two flow
drafts to stable JVM method identities. The CI evidence bundle contains two exact-entry Context
reports plus one task-wide JFR analysis. The generated joined report attaches both layers to each
flow step while retaining their scope and provenance. It does not rewrite the knowledge manifest.

The combined run enabled exact entry collection and JFR in the same test worker. It reported 158
tests: 97 passed, 61 were skipped, and none failed. Exact entry collection observed 107 of 253
selected methods across the two reports, with no collection errors or dropped observations. JFR
sampled 56 selected methods and 228 Spring Web methods overall.
`InvocableHandlerMethod.invokeForRequest` illustrates the complementary signals: it was outside the
exact-entry inventory, yet appeared in three CPU and 37 allocation stack occurrences. JFR still
provides no generic root/non-root Context state. The join therefore uses the profile to discover and
prioritize executed regions, and exact entry collection to determine Context state where collection
applies.

The joined Callable draft is still `NOT_ESTABLISHED`. Several dispatch steps have exact evidence,
but the Callable return handler, async-start overload, and worker callback do not have attributed
entry evidence. Existing observations for result dispatch and redispatch consumption cannot be
assumed to belong to that flow. This is the useful output: a concrete missing-test candidate plus
the precise evidence needed before any instrumentation conclusion or quality gate.

## Guardrails

- A static path is possible execution, not proof of execution.
- An `invokedynamic` call site is preserved explicitly; lambda implementation handles can create a
  concrete edge, while other bootstrap semantics may still require interpretation.
- Absence of a static edge is not proof that a reflective or configured flow cannot occur.
- A highly central method is not automatically important to instrumentation.
- Documentation describes supported semantics but may omit internal and version-specific details.
- Profiles rank and discover paths but do not prove absence.
- Customer evidence establishes a concrete occurrence under recorded conditions, not universal
  prevalence or correctness.
- Raw customer payloads do not belong in the reusable graph; retain secure references and sanitized
  derived evidence with access classification.
- Context presence does not prove correct lineage.
- Simplification rules and flow bindings must be inspectable and reversible.
- Quality gates require reviewed scenario expectations and complete collection.
