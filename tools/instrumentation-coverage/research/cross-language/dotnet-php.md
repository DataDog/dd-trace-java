# Instrumentation coverage feasibility: .NET and PHP

Read-only source assessment of local snapshots; no builds or runtime experiments performed. Sources below are repository-relative; repos live at `/Users/andrea.marziali/go/src/github.com/DataDog/`. Both checkouts are clean and on `andrea.marziali/dbm-dynamic-service`, tracking the same-named origin branch, NOT established as upstream default branch. .NET AGENTS.md and its automatic-instrumentation development guide read; no PHP AGENTS.md found.

- dd-trace-dotnet: `8fb709cb65b0c734a18e8e0112729c23d267b312`
- dd-trace-php: `d56241f5a68ddf180fd234f6448af9a788872697`

## Conclusion

Both have credible implementation paths using existing agent-enabled integration tests and existing profiling correlation. Neither has been demonstrated to provide our proposed functionality-coverage artifact as-is. A common analysis/portal format is feasible; an identical Java-style generic Context boolean is not established across the tracers. Explicitly distinguish native active scope/span, generic OTel context where present, correlation availability, and correctness of expected lineage.

Existing test execution is reusable workload, but ordinary assertion results and finished spans alone do not contain a record of all executed library methods or intervals lacking context. New collection or configured profile artifact capture is required.

## .NET: verified building blocks

- `tracer/src/Datadog.Trace/AsyncLocalScopeManager.cs:15-20,29-55,58-77`: scope lives in AsyncLocal; activation and closure replace/restore it. With profiler tracker enabled, scope changes reset or set native local-root/span identifiers. This is scoped span state, not proof of a Java-equivalent arbitrary Context API.
- `tracer/src/Datadog.Trace/Tracer.cs:131-148`: public ActiveScope first consults distributed tracer, while InternalActiveScope uses the native scope manager. A collector must specify which source it observes and handle interop rather than treating one internal null as universal absence.
- `tracer/src/Datadog.Trace/ContinuousProfiler/ContextTracker.cs:25-40,52-62,84-86,128-145`: thread-local pointer to guarded native IDs, enabled only when profiler ready and code-hotspots enabled; reset writes zero. Disabled or failed tracker must mean attribution unknown/unavailable, not proven context missing.
- `profiler/src/ProfilerEngine/Datadog.Profiler.Native/RawSampleTransformer.cpp:23-46`: sample has timestamp, optional local-root/span labels, thread metadata, symbolized stack. This is already a useful foundation for attributed/unattributed execution evidence.
- `docs/development/AutomaticInstrumentation.md:35-52,58-95`: existing sample-app integration tests, version-matrix/CI workflow, CallTarget begin/end/async-end hooks. Hooks are a credible selective observer route, but production instrumentation callbacks alone cannot reveal execution outside matched methods. Adding broad observation requires selecting extra methods and verifying composition/order with production instrumentation.
- `tracer/test/Datadog.Trace.ClrProfiler.IntegrationTests/HttpMessageHandlerTests.cs:62-103`: existing matrix configures instrumentation, runs sample process against mock agent, awaits spans. This concrete test is marked flaky at line 61, so prefer a stable existing ASP.NET Core sample scenario for a first gate.
- `profiler/test/Datadog.Profiler.IntegrationTests/Helpers/EnvironmentHelper.cs:180-200,414-416`: profiler harness configures native loading, profiling, pprof output. IMPORTANT it sets DD_TRACE_ENABLED=0 by default (line198), so blindly reusing a profiler-only test harness would not meet the agent-enabled tracing requirement. Explicitly enable tracing and verify tracer/profiler loaded.

### Proposed minimal .NET pilot (design, not executed)

Use an existing stable ASP.NET Core integration sample and its request success/error scenarios, pin one library/runtime version, enable continuous profiling in the child process with local pprof capture, and retain existing span assertions. Tag scenarios outside production Context or run them in isolated process windows. Inventory selected ASP.NET request execution, handler, exception and response methods from the selected assemblies; map stacks to these capabilities. Short tests may need repeated existing requests for sampling visibility, explicitly reported as an additional workload variant.

For exact context verification, add test-only selective method probes reading active scope and returning without creating spans/scopes; prove production activation happens before the chosen method-body observation. Distinguish method entry from await continuations; compiler-generated state machine frames and async completion need mapping. Profile sampling is broad discovery, not exhaustive methods/branches or scope-integrity proof.

Safe initial gates: collector/tracer actually enabled, no dropped evidence, existing assertions preserved, known stable scenario entry points still exercised and retain expected scope lineage/restoration. No hard gate on a sampled method disappearing, zero uncorrelated samples, or global percentage.

## PHP: verified building blocks and an important observer hazard

- `tracer/hook/uhook.stub.php:133-164`: install_hook provides begin/end callbacks on function/method, Closure, Generator, or file targets and removal. This is a plausible selective test-only observer mechanism. Do NOT call HookData::span() (documented at lines39-53 to create/reuse a span) from the observer.
- `tracer/functions.c:2202-2218`: **DDTrace\\active_span() is not a pure read**. It invokes dd_ensure_root_span(), which creates a root span in eligible state when root generation is enabled. A naive observer could erase the gray area it is measuring.
- `tracer/functions.c:2375-2383`: active_stack returns the stack object without that root-span creation path. Stack existence alone is not active-context presence: it can have no active span. Native field access or a dedicated pure read is safer than assuming non-null stack means covered.
- `tracer/profiling.c:9-19`: existing native ddtrace_get_profiling_context() reads active stack/root/active span and tracing enablement, returning zero IDs otherwise, without root creation. Comment explicitly explains legitimate transient absence during last-span close. This is a promising non-mutating correlation input, not generic Context evidence.
- `profiling/src/profiling/mod.rs:1553-1587`: profiler reads that accessor on a PHP thread, attaches local-root/span labels when nonzero; absent tracer accessor is a no-op. Again unavailable profiler/tracer integration must be separate from actual absence.
- `src/DDTrace/OpenTelemetry/Context.php:27-42,82-105,164-185`: OTel Context has general key/value storage and a root object, but resolve reconciles span state, invokes active_span, and activates/reconstructs parents. Generic-context observation needs a deliberately side-effect-free storage inspection, not blindly invoking the reconciliation API. This semantics differs from Java's identity check.
- `tests/Common/WebFrameworkTestCase.php:55-76,103-115`: framework tests launch/manage application webserver and provide tracing-related environment, so collection belongs in the application process, not only the PHPUnit runner.
- `tests/Integrations/Symfony/V5_2/CommonScenariosTest.php:10-42,45-85`: existing version-specific web requests and nested trace assertions, suitable reusable behavioral evidence.
- `.gitlab/generate-tracer.php:549-569,659-671`: CI integration template runs actual make targets, collects JUnit and tested-version artifacts, and depends on compiled extension. Coverage artifact publication could use this existing boundary.
- `.github/workflows/prof_correctness.yml:104-107`: existing CI profile correctness workflow uses DD_PROFILING_OUTPUT_PPROF and local profile files. This proves an export route, not that Symfony jobs currently emit profiles.
- `tests/OpenTelemetry/Integration/Context/Fiber/FiberTest.php:86-98,126-140`: assertions exercise active span across fiber suspension/resumption and outer parent restoration. Useful semantic controls; current hook examples create spans intentionally and should not be copied into observer callbacks.

### Proposed minimal PHP pilot (design, not executed)

Use the existing Symfony common-scenarios webserver harness on one pinned PHP/framework version. Capture local profiling artifacts from actual request processes; map request handling/controller/error/view frames to capabilities and correlate samples with native IDs. Alternatively install selected observational hooks with a non-mutating native accessor, recording entry state/counts into a bounded per-request artifact. Request auto-root generation may make almost every frame attributed: that is evidence of ambient request context, NOT complete instrumentation of all framework functionality. Add identity/expected lineage checks where scope integrity is the question.

Validate hook ordering against existing production hooks; generic fibers and generators require execution-context identity instead of only OS thread. Include deliberate negative control, restoration after exceptions, and intentional no-context/suppression scenarios. Explicit collection windows exclude startup/shutdown; preserve and explain expected context-free phases.

Safe initial gates: no observer-created spans, unchanged existing trace assertions, expected lifecycle restoration and known capability scenario checks. Report root/empty state as an investigation candidate; ordinary lifecycle teardown can legitimately have no active span. Do not gate on full coverage percentage or sampled absences.

## Shared product implications

A shared artifact can encode repository/version/runtime, test scenario/process, logical execution unit, capability map version, observation source (sample vs entry), location, native context-state semantic, profile correlation, expected lineage status, collector health, exclusions and evidence confidence. Unknown must remain first-class. Generic Context, native active span and profiling attribution are three different fields, not one green/red flag.

CI produces artifacts; toolkit uses evidence to suggest tests/instrumentation and define scoped gates; portal presents capability coverage and trends. Both tracers support this architecture in source. Cross-language correctness and overhead remain to be measured in pilots, and an independent capability inventory is required to report unobserved functionality.
