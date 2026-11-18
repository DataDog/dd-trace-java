# Cross-SDK consistency

Behaviour that must match other Datadog SDKs, specs (including OpenTelemetry), and the agent or backend contract.

**When planning:** take the rules below into account when designing the change.

**When reviewing:** report a finding when the diff matches a rule's `Flag:` line, or when it clearly diverges from a spec or another SDK in a way no rule covers (mark it `no rule`), naming the spec, registry, or contract you compared against. Only the cross-SDK reviewer may consult external references.

- Check changes to shared tracer behavior (e.g., context propagation, sampling, configuration, or telemetry) for cross-language impact, using relevant specifications and other tracer implementations as references.
- When an issue stems from the shared design or specification, explicitly flag it as a **design issue** requiring coordination with the affected tracer teams.
- Explain why a Java-only fix is insufficient or would create divergence.
- Distinguish this from a Java implementation bug that can be fixed locally to match the agreed behavior.
- Validation across all dd-trace- repositories is not required.

Other repositories:
- [.NET (`dd-trace-dotnet`)](https://github.com/DataDog/dd-trace-dotnet)
- [Python (`dd-trace-py`)](https://github.com/DataDog/dd-trace-py)
- [Node.js (`dd-trace-js`)](https://github.com/DataDog/dd-trace-js)
- [Go (`dd-trace-go`)](https://github.com/DataDog/dd-trace-go)
- [PHP (`dd-trace-php`)](https://github.com/DataDog/dd-trace-php)
- [Ruby (`dd-trace-rb`)](https://github.com/DataDog/dd-trace-rb)
- [C++ (`dd-trace-cpp`)](https://github.com/DataDog/dd-trace-cpp)
- [Rust (`libdatadog`)](https://github.com/DataDog/libdatadog)

## 1. Configuration

### XSDK-1.1: Reuse the configuration name and version from the Feature Parity Dashboard for keys other SDKs already have
- Flag: a new `supported-configurations.json` key that duplicates an existing cross-SDK setting under a different name, or uses version `A` for a key that the dashboard already lists with the same behaviour. See [add_new_configurations.md](../../../../docs/add_new_configurations.md#adding-a-configuration).

## 2. Propagation

### XSDK-2.1: Emit W3C `tracestate` with at most one `dd=` member, keeping only the first one, as extraction does
- Flag: injection or export code that copies every `dd=` list member verbatim, or that parses `tracestate` differently from `W3CPTagsCodec` extraction. Duplicate keys break the W3C trace-context spec.

## 3. Specs and contracts

### XSDK-3.1: Follow the OpenFeature spec for evaluation results, and call out behaviour changes explicitly
- Flag: a flag-evaluation path that returns `Reason.DEFAULT` (or a default value with no error) for a missing flag instead of the `FLAG_NOT_FOUND` error code, or that changes an evaluation result with no mention in the PR description.

### XSDK-3.2: Keep published version strings, propagation tag names and other values that other SDKs, the backend or telemetry parse in their existing format
- Flag: a change to the format of the tracer version string (for example, short to full git hash), a telemetry field or a payload field, or a rename of a propagation tag (`_dd.p.*`) or header that has already shipped. This includes changes made to fix a local build or tooling problem.
