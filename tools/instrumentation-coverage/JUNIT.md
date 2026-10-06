# JUnit Jupiter observation

Set `"adapter": "junit"` in the module's `coverage/observation.json`. The regular `workflow.py run`
command then enables the Jupiter extension through service autodetection, disables Jupiter parallel
execution, and uses the same run/worker output layout and deterministic report join as Spock.

```json
{
  "schemaVersion": 1,
  "adapter": "junit",
  "attribution": "serialized-test-window",
  "classes": ["io.reactivex.rxjava3.core.Observable"],
  "requiredTransformed": ["io.reactivex.rxjava3.core.Observable"]
}
```

This observation file still needs the module's `library.json` and reviewed `flows.json` for a full
functional report. RxJava 3 has been used to validate collection, but has no committed functional
catalog yet. Use `validate-junit.py` to exercise that adapter without inventing flow definitions.

## Lifecycle

The adapter activates only for subclasses of `datadog.trace.agent.test.AbstractInstrumentationTest`.
It is packaged in the observer jar, with compile-only Jupiter API dependencies. It does not package
another Context implementation or change production instrumentation.

1. Before the inherited `initAll` method, load selected observation classes and install the observer.
   Keep collection paused while the harness installs its production transformer and tracer.
2. At test-body entry, verify the registered tracer, active harness transformer, transformed-class
   inventory and bootstrap Context. A missing required transformation fails visibly.
3. Collect during `@Test`, test-template invocations (including parameterized and repeated tests),
   and dynamic test bodies. Each invocation has a human display name and a separate stable scenario
   ID derived from its JUnit unique ID. Setup/teardown and parameter-provider/factory execution are
   outside the collection window.
4. Pause in a `finally` block so a failed test cannot leak collection into teardown. Preserve the
   original test failure. The collector never activates Context.
5. Before inherited `tearDownAll`, reset observation while production instrumentation is still
   installed and write the report. Always allow harness teardown to execute if collection/reporting
   fails. An after-all callback provides fallback cleanup for setup failure.

Each observation records its scenario ID, display name, attribution mechanism, and confidence.
Initiating-thread attribution is `EXACT`; worker work assigned through the serialized active test
window is `TEMPORAL`; entries outside a scenario are `NONE`. These values describe attribution
quality independently of root/non-root Context state. Worker attribution still does not prove
request lineage. The supported baseline is the standard repository harness;
custom replacement of its lifecycle, independently instrumented loaders, and nested harness
reinitialization have not been validated. Dynamic-body interception is implemented; the real RxJava
validation covers ordinary and parameterized invocations.

## Reproduce the integration validation

From the prototype worktree root:

```sh
python3 tools/instrumentation-coverage/validate-junit.py
```

The script builds the observer and runs the unchanged RxJava 3 `test` task on JDK 21 without and
with collection. It observes the five core reactive classes: Observable, Flowable, Single, Maybe
and Completable. It preserves test XML, Context reports, resolved artifacts, and a compact
`validation.json` in a fresh module build directory.

The current validated run had 70 passing tests in both modes, five healthy reports, and 70 distinct
attributed scenario IDs, including 52 parameterized invocations. Required core types were
production-transformed and Context came from bootstrap. Counts describe this validation suite and
selected inventory, not all RxJava execution.

Additional collector tests verify assertion-error identity, setup/teardown exclusion after a
failure, distinct invocation labels and preservation of the current Context.
