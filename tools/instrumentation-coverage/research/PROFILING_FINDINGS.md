# Profiling instead of library method-entry observation

## Question

Can profiling data from the existing agent-enabled tests replace selected library method-entry
observation, while still showing exercised functionality and Context gray areas?

## Experiment

The unchanged Spring WebMVC 6 `test` task ran on JDK 21 with a standard OpenJDK JFR recording and
without the context-coverage method observer. The run reported the same 158 cases: 97 executed, 61
skipped, and no failures or errors. The recording covers the whole test worker JVM, including setup
and shutdown. A local analyzer reads full JFR stacks and compares them with the existing 253-method
inventory and the union of the two earlier entry reports.

Two recordings tested JFR's `profile` configuration:

| Recording | CPU/native samples | Selected methods in CPU stacks | Selected methods in any profile stack | Spring Web methods in any profile stack |
| --- | ---: | ---: | ---: | ---: |
| `method-profiling=high` | 842 | 5 / 253 | 25 / 253 | 134 |
| `method-profiling=max` | 1,472 | 18 / 253 | 31 / 253 | 166 |
| Exact entry evidence, for reference | — | — | 107 / 253 | selected inventory only |

The denser recording contained 3,544 allocation samples. Allocation stacks supplied 25 of the 31
sampled selected methods and exposed controller invocation, argument resolution, message conversion,
routing, and error handling. Blocking events did not retain a Spring Web frame in this run.

These counts are observations from separate runs and can vary with sampling. A sampled method is
evidence that the path ran. A method absent from the profile remains unknown; it is not evidence that
the method did not execute.

## What profiling adds

- **Inventory discovery.** The dense profile found 135 Spring Web methods outside the declared
  ten-class inventory. This includes `InvocableHandlerMethod.invokeForRequest`,
  `ServletInvocableHandlerMethod.invokeAndHandle`, message conversion, and exception resolution.
  Profiling can therefore suggest missing classes and functionality without transforming them. This
  also bypasses the prototype collector's optional-dependency transformation limitation for those
  invocation classes.
- **Runtime priority.** Stack occurrence and allocation weight can rank exercised paths for
  investigation. They measure sampled activity, not invocation counts or behavioral importance.
- **Path topology.** One sample can expose a chain from servlet dispatch through routing and
  controller invocation, making it useful for drafting capability maps and candidate tests.
- **Additional signals.** CPU, allocation, lock, park, and I/O evidence can explain where test time
  or allocation occurs. Short tests may yield little CPU evidence even at denser sampling.

Three selected configuration methods appeared in profile stacks without entry evidence. The profile
covers the full JVM lifetime, while entry collection only covers test feature bodies, so this exposes
a collection-window difference rather than a contradiction.

## What profiling does not establish

Standard JFR samples do not contain generic `Context.current()` state. The recordings contain no
`datadog.*` event types because `InstrumentationSpecification` builds its tracer with
`TestProfilingContextIntegration`; that fixture counts scope attach/detach and does not emit the
production `datadog.Timeline` event.

Even with production timeline events, span IDs and operation names describe tracing attribution.
They do not prove that an arbitrary non-span value is present in generic Context, that the expected
Context identity survived, or that every executed method was sampled.

A method-entry-free Context experiment is feasible by emitting a duration event when generic Context
changes and joining samples by thread and time. That is a small Context-lifecycle observation hook,
not profiling alone. The current legacy `AgentTracer` Context manager ignores `ContextListener`
registration, so the prototype would need an explicit bridge or test-only lifecycle hook before this
can be measured faithfully.

## Recommended model

Use profiling as the broad discovery and prioritization layer:

1. map sampled stacks to library functionality;
2. identify important classes outside the declared inventory;
3. rank paths by CPU, allocation, blocking, or other sampled activity;
4. generate candidate scenarios for tests.

Use targeted Context evidence for verification:

1. record generic root/non-root state at reviewed boundaries or through lifecycle intervals;
2. correlate the same request or operation across asynchronous handoffs;
3. verify proposed tests or instrumentation changes with exact before/after evidence.

The UI can show profile activity as heat over the functionality map and overlay exact Context states
where available. “Not sampled,” “not observed by the entry collector,” and “observed at root Context”
must remain separate states.

