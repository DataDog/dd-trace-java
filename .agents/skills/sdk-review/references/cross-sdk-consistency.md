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
