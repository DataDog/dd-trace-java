# Tracer Flare Contents

## 📦 Files Included in the ZIP Archive

| # | File Name | Description | Implementation Link |
|---|-----------|-------------|-------------------|
| 1 | `tracer_python_{pid}.log` | **Python Tracer Logs**<br>Structured JSON-formatted logs from the Python tracer captured at the specified log level (e.g., DEBUG). Each line contains: timestamp, level, logger name, filename, module, function name, line number, message, thread info, process info, exceptions, and stack traces. | [Setup Code](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L164-L192)<br>[JSON Formatter](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/json_formatter.py#L7-L98) |
| 2 | `tracer_native_{pid}.log` | **Native Tracer Logs**<br>Low-level logs from the native (C/Cython) tracer components. Only included when `config._trace_writer_native` is enabled. | [Setup Code](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L194-L197) |
| 3 | `tracer_config_{pid}.json` | **Tracer Configuration Snapshot**<br>Complete snapshot of tracer configuration including: service name, environment, version, APM settings, integration configs, sampling rules, tags, and API key (last 4 chars only, rest redacted). | [Generation Code](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L102-L123) |

## 📋 Metadata Sent with the Flare

| # | Field | Description | Implementation Link |
|---|-------|-------------|-------------------|
| 4 | `source` | **Source Identifier**<br>Always set to `"tracer_python"` to identify the tracer language/type. | [Code](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L227) |
| 5 | `case_id` | **Zendesk Case ID**<br>Support ticket ID where the flare will be attached. Must be numeric or follow pattern `{number}-with-debug`. Cannot be "0". | [Code](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L228)<br>[Validation](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L142-L162) |
| 6 | `hostname` | **Hostname**<br>The hostname of the machine running the tracer. | [Code](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L229) |
| 7 | `email` | **User Email**<br>Email address (user handle) of the person requesting the flare. | [Code](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L230) |
| 8 | `uuid` | **Request UUID**<br>Unique identifier from the AGENT_TASK config to prevent race conditions and duplicate sends. | [Code](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L231) |
| 9 | `flare_file` | **ZIP Archive**<br>Compressed archive containing all log files and configuration. Filename format: `tracer-python-{case_id}-{timestamp}-debug.zip` | [ZIP Creation](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L201-L211)<br>[Packaging](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L233-L241) |

## 🚀 Sending Mechanism

| Component | Details | Link |
|-----------|---------|------|
| **Endpoint** | `/tracer_flare/v1` on the Datadog Agent | [Constant](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L24) |
| **Method** | HTTP POST with multipart/form-data | [Send Code](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L259-L288) |
| **Content-Type** | `multipart/form-data; boundary={BOUNDARY}` | [Payload Generation](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L219-L253) |
| **Timeout** | 5 seconds (default) | [Constant](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L27) |
| **Working Directory** | `tracer_flare/` | [Constant](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L22) |

## 🔄 Workflow

| Phase | Trigger | Action | Link |
|-------|---------|--------|------|
| **Phase 1: Prepare** | Remote Config `AGENT_CONFIG` with `log_level` | Creates flare directory, sets up file handlers for log capture, generates config file | [Prepare Method](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L64-L83)<br>[Handler](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/handler.py#L55-L82) |
| **Phase 2: Send** | Remote Config `AGENT_TASK` with `task_type: "tracer_flare"` | Reverts logging config, packages files into ZIP, sends to agent, cleans up | [Send Method](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py#L85-L100)<br>[Handler](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/handler.py#L85-L117) |
| **Initialization** | Tracer startup (when Remote Config enabled) | Creates `Flare` instance and registers with Remote Config poller | [Registration](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/remoteconfig/products/client.py#L7-L17) |
| **Subscriber** | Polls Remote Config for AGENT_CONFIG/AGENT_TASK | Monitors for flare requests and handles stale request cleanup (20 min timeout) | [TracerFlareSubscriber](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/_subscribers.py#L18-L89) |

## 📚 Additional Resources

| Resource | Description | Link |
|----------|-------------|------|
| **Release Notes** | Feature announcement and overview | [Release Note](https://github.com/DataDog/dd-trace-py/blob/main/releasenotes/notes/add-tracer-flare-65e275bca27631dd.yaml) |
| **Test Suite** | Unit tests demonstrating flare functionality | [Tests](https://github.com/DataDog/dd-trace-py/blob/main/tests/internal/test_tracer_flare.py) |
| **Manual Test Script** | Script for manually testing flare generation | [Manual Test](https://github.com/DataDog/dd-trace-py/blob/main/scripts/trace_flares/test_flare_manual.py) |
| **Main Flare Module** | Core flare implementation | [flare.py](https://github.com/DataDog/dd-trace-py/blob/main/ddtrace/internal/flare/flare.py) |

---

**Note:** The Datadog Agent receives the flare and forwards it to the Datadog backend, which attaches it to the specified Zendesk support ticket. The API key is intentionally not sent in the request headers as the agent adds it when forwarding.

## Summary

### What's Included:
✅ Python tracer logs (JSON formatted)  
✅ Native tracer logs (if native writer enabled)  
✅ Complete tracer configuration (with redacted API key)  

### Metadata:
✅ Source identifier (`tracer_python`)  
✅ Case ID (Zendesk ticket)  
✅ Hostname  
✅ Email (user handle)  
✅ UUID (for deduplication)  

### Process:
1. **Remote trigger** via Datadog Remote Configuration
2. **Prepare phase** - sets up logging and config capture
3. **Send phase** - packages everything into ZIP and sends to agent
4. **Cleanup** - removes temporary files and reverts logging config

---

## Related Documentation

**For using tracer flares to generate benchmarks**, see:
- [TRACER_FLARE_BENCHMARK_REQUIREMENTS.md](TRACER_FLARE_BENCHMARK_REQUIREMENTS.md) - Detailed requirements for benchmark generation
- [TRACER_FLARE_BENCHMARK_SUMMARY.txt](TRACER_FLARE_BENCHMARK_SUMMARY.txt) - Quick reference summary (Google Docs friendly)

