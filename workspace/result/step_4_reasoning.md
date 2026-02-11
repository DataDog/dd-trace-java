## Step 4 reasoning log

This file captures per-key reasoning for Step 4 (code-based inference).

- Each entry includes: mapping (DD_ env var ↔ internal config token), code evidence references, and the inference used to write the description.
- If evidence is insufficient to write a self-contained description, the key+version is added to `unknown_configurations.json`.

### `DD_ACTION_EXECUTION_ID` (A)

- **How it was found**: `DD_ACTION_EXECUTION_ID` is referenced directly as an env var name in the CI Visibility AWS CodePipeline provider.
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ci/AwsCodePipelineInfo.java:13`: declares the env var constant.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ci/AwsCodePipelineInfo.java:32`: value is used as `ciJobId(...)`.
- **Inference**: This config provides the **AWS CodePipeline action execution ID** used by CI Visibility as the **CI job identifier** for AWS CodePipeline builds.

### `DD_AI_GUARD_ENDPOINT` (A)

- **Mapping**: `DD_AI_GUARD_ENDPOINT` ↔ internal config token `AIGuardConfig.AI_GUARD_ENDPOINT` (`"ai_guard.endpoint"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2974`: `aiGuardEndpoint = configProvider.getString(AI_GUARD_ENDPOINT)`.
  - `dd-java-agent/agent-aiguard/src/main/java/com/datadog/aiguard/AIGuardInternal.java:83-100`: uses `config.getAiGuardEndpoint()`, defaults to `https://app.<site>/api/v2/ai-guard`, appends `/evaluate`.
- **Inference**: This config sets the base URL of the **AI Guard REST API** endpoint used by the agent/SDK.

### `DD_API_KEY_FILE` (A)

- **Mapping**: `DD_API_KEY_FILE` ↔ internal config token `GeneralConfig.API_KEY_FILE` (`"api-key-file"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1348-1359`: reads a file path from `API_KEY_FILE`, loads and trims file contents as the API key.
- **Inference**: This config points to a file containing the **Datadog API key**; when set, the tracer loads the key from that file.

### `DD_API_SECURITY_DOWNSTREAM_REQUEST_ANALYSIS_SAMPLE_RATE` (A)

- **Mapping**: `DD_API_SECURITY_DOWNSTREAM_REQUEST_ANALYSIS_SAMPLE_RATE` ↔ internal config token `AppSecConfig.API_SECURITY_DOWNSTREAM_REQUEST_ANALYSIS_SAMPLE_RATE` (`"api-security.downstream.request.analysis.sample_rate"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2249-2253`: reads the downstream body analysis rate with a fallback to `API_SECURITY_DOWNSTREAM_REQUEST_ANALYSIS_SAMPLE_RATE` (legacy name).
  - `dd-java-agent/appsec/src/main/java/com/datadog/appsec/api/security/ApiSecurityDownstreamSamplerImpl.java:13-44`: sampling rate controls whether a downstream HTTP client request is selected for analysis.
- **Inference**: This is a legacy/alternate name for the downstream body analysis sample rate; it controls **how often downstream HTTP client request bodies are analyzed** for API Security.

### `DD_API_SECURITY_ENABLED` (A)

- **Mapping**: `DD_API_SECURITY_ENABLED` ↔ internal config token `AppSecConfig.API_SECURITY_ENABLED` (`"api-security.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2236-2238`: reads `apiSecurityEnabled`.
  - `dd-java-agent/appsec/src/main/java/com/datadog/appsec/AppSecSystem.java:200-216`: if enabled and AppSec active, initializes API Security sampler and AppSec span post-processing.
- **Inference**: Enables the API Security subsystem (sampling + span post-processing when AppSec is active).

### `DD_API_SECURITY_ENDPOINT_COLLECTION_ENABLED` (A)

- **Mapping**: `DD_API_SECURITY_ENDPOINT_COLLECTION_ENABLED` ↔ internal config token `AppSecConfig.API_SECURITY_ENDPOINT_COLLECTION_ENABLED` (`"api-security.endpoint.collection.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:359-363`: reads the boolean.
  - `telemetry/src/main/java/datadog/telemetry/TelemetrySystem.java:84-86`: when enabled, adds `EndpointPeriodicAction()` to telemetry.
- **Inference**: Enables/disables collection + telemetry reporting of API endpoint information (used by API Security).

### `DD_API_SECURITY_ENDPOINT_COLLECTION_MESSAGE_LIMIT` (A)

- **Mapping**: `DD_API_SECURITY_ENDPOINT_COLLECTION_MESSAGE_LIMIT` ↔ internal config token `AppSecConfig.API_SECURITY_ENDPOINT_COLLECTION_MESSAGE_LIMIT` (`"api-security.endpoint.collection.message.limit"`).
- **Evidence**:
  - `telemetry/src/main/java/datadog/telemetry/TelemetryRequest.java:239-252`: limits the number of endpoint events written into one telemetry request.
- **Inference**: Caps how many endpoint records can be emitted per telemetry message.

### `DD_API_SECURITY_SAMPLE_DELAY` (A)

- **Mapping**: `DD_API_SECURITY_SAMPLE_DELAY` ↔ internal config token `AppSecConfig.API_SECURITY_SAMPLE_DELAY` (`"api-security.sample.delay"`).
- **Evidence**:
  - `dd-java-agent/appsec/src/main/java/com/datadog/appsec/api/security/ApiSecuritySamplerImpl.java:42-56`: converts `Config.get().getApiSecuritySampleDelay()` to milliseconds and uses it as an expiration window for sampling decisions.
- **Inference**: Sets the minimum delay before sampling the same endpoint again (throttles API Security sampling).

### `DD_APPLICATION_KEY_FILE` (A)

- **Mapping**: `DD_APPLICATION_KEY_FILE` ↔ internal config token `GeneralConfig.APPLICATION_KEY_FILE` (`"application-key-file"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1365-1374`: reads a file path from `APPLICATION_KEY_FILE`, loads and trims file contents as the application key.
- **Inference**: This config points to a file containing the **Datadog application key**; when set, the tracer loads the key from that file.

### `DD_APPSEC_AUTOMATED_USER_EVENTS_TRACKING` (C)

- **Mapping**: `DD_APPSEC_AUTOMATED_USER_EVENTS_TRACKING` ↔ internal config token `AppSecConfig.APPSEC_AUTOMATED_USER_EVENTS_TRACKING` (`"appsec.automated-user-events-tracking"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2213-2216`: uses this value as the `trackingMode` input to `UserIdCollectionMode.fromString(...)` when the explicit mode is not set.
  - `internal-api/src/main/java/datadog/trace/api/UserIdCollectionMode.java:20-60`: when `collectionMode` is null, `trackingMode` maps `safe → anonymization`, `extended → identification`, else `disabled`.
- **Inference**: Legacy control for automated user-event tracking; it selects the effective user ID collection mode when the explicit mode config is not set.

### `DD_APPSEC_AUTO_USER_INSTRUMENTATION_MODE` (C)

- **Mapping**: `DD_APPSEC_AUTO_USER_INSTRUMENTATION_MODE` ↔ internal config token `AppSecConfig.APPSEC_AUTO_USER_INSTRUMENTATION_MODE` (`"appsec.auto-user-instrumentation-mode"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/UserIdCollectionMode.java:43-50`: mode parsing supports `identification/ident`, `anonymization/anon`, else disabled.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2213-2216`: explicit mode takes precedence over the tracking-mode config.
- **Inference**: Controls the effective automated user instrumentation mode (identification vs anonymization vs disabled).

### `DD_APPSEC_HTTP_BLOCKED_TEMPLATE_HTML` (C)

- **Mapping**: `DD_APPSEC_HTTP_BLOCKED_TEMPLATE_HTML` ↔ internal config token `AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_HTML` (`"appsec.http.blocked.template.html"`).
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/blocking/BlockingActionHelper.java:195-210`: if configured and file exists, reads the HTML template from that file; otherwise uses the default embedded template.
- **Inference**: Path to a custom HTML response template used for AppSec blocking responses.

### `DD_APPSEC_HTTP_BLOCKED_TEMPLATE_JSON` (C)

- **Mapping**: `DD_APPSEC_HTTP_BLOCKED_TEMPLATE_JSON` ↔ internal config token `AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_JSON` (`"appsec.http.blocked.template.json"`).
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/blocking/BlockingActionHelper.java:211-225`: if configured and file exists, reads the JSON template from that file; otherwise uses the default embedded template.
- **Inference**: Path to a custom JSON response template used for AppSec blocking responses.

### `DD_APPSEC_IPHEADER` (A)

- **Mapping**: `DD_APPSEC_IPHEADER` ↔ internal config token `AppSecConfig.APPSEC_IP_ADDR_HEADER` (`"appsec.ipheader"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1994-2001`: uses `appsec.ipheader` as a fallback to set the `traceClientIpHeader` when `trace.client-ip-header` is not set.
- **Inference**: Specifies which HTTP header should be treated as the client IP header for client-IP resolution (fallback behavior).

### `DD_APPSEC_MAX_STACKTRACES` (A)

- **Mapping**: `DD_APPSEC_MAX_STACKTRACES` ↔ internal config token `AppSecConfig.APPSEC_MAX_STACKTRACES_DEPRECATED` (`"appsec.max.stacktraces"`, legacy name).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2223-2227`: reads `appSecMaxStackTraces` with a fallback to the deprecated token.
  - `dd-java-agent/appsec/src/main/java/com/datadog/appsec/gateway/AppSecRequestContext.java:690-701`: drops stack trace events once the configured maximum is reached.
- **Inference**: Legacy alias controlling the maximum number of AppSec stack trace events kept per request.

### `DD_APPSEC_MAX_STACKTRACE_DEPTH` (A)

- **Mapping**: `DD_APPSEC_MAX_STACKTRACE_DEPTH` ↔ internal config token `AppSecConfig.APPSEC_MAX_STACKTRACE_DEPTH_DEPRECATED` (`"appsec.max.stacktrace.depth"`, legacy name).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/util/stacktrace/StackUtils.java:70-77`: limits stack frames captured based on `Config.get().getAppSecMaxStackTraceDepth()`.
- **Inference**: Legacy alias controlling the maximum number of stack frames captured for AppSec stack traces.

### `DD_APPSEC_RASP_ENABLED` (A)

- **Mapping**: `DD_APPSEC_RASP_ENABLED` ↔ internal config token `AppSecConfig.APPSEC_RASP_ENABLED` (`"appsec.rasp.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:253`: reads the boolean.
  - `dd-java-agent/instrumentation/java/java-net/java-net-1.8/src/main/java/datadog/trace/instrumentation/java/net/URLSinkCallSite.java:58-90`: RASP callback is gated by `Config.get().isAppSecRaspEnabled()` and can trigger request blocking.
- **Inference**: Enables/disables RASP runtime protections (call-site checks) which may block dangerous operations based on AppSec decisions.

### `DD_APPSEC_REPORTING_INBAND` (A) — unknown

- **Mapping**: `DD_APPSEC_REPORTING_INBAND` ↔ `AppSecConfig.APPSEC_REPORTING_INBAND` (`"appsec.reporting.inband"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2189-2191`: value is read into `appSecReportingInband`.
  - No usages of `Config.get().isAppSecReportingInband()` found in the repo.
- **Inference**: Insufficient evidence of runtime behavior. Added to `unknown_configurations.json`.

### `DD_APPSEC_REPORT_TIMEOUT` (A) — unknown

- **Mapping**: `DD_APPSEC_REPORT_TIMEOUT` ↔ `AppSecConfig.APPSEC_REPORT_TIMEOUT_SEC` (`"appsec.report.timeout"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2193-2196`: value is read into `appSecReportMaxTimeout` / `appSecReportMinTimeout`.
  - No usages of `Config.get().getAppSecReportMaxTimeout()` / `getAppSecReportMinTimeout()` found in the repo.
- **Inference**: Insufficient evidence of runtime behavior. Added to `unknown_configurations.json`.

### `DD_APPSEC_SCA_ENABLED` (B)

- **Mapping**: `DD_APPSEC_SCA_ENABLED` ↔ internal config token `AppSecConfig.APPSEC_SCA_ENABLED` (`"appsec.sca.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2937-2943`: warns that SCA requires telemetry/dependency collection.
  - `dd-trace-core/src/main/java/datadog/trace/common/sampling/Sampler.java:104-107`: SCA enabled contributes to “ASM enabled” for sampling decisions.
- **Inference**: Enables AppSec SCA and affects sampling/telemetry expectations.

### `DD_APPSEC_STACKTRACE_ENABLED` (A)

- **Mapping**: `DD_APPSEC_STACKTRACE_ENABLED` is the legacy env-var alias for AppSec stack trace enablement (deprecated token `"appsec.stacktrace.enabled"`). It controls the same behavior as `DD_APPSEC_STACK_TRACE_ENABLED` (`"appsec.stack-trace.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2218-2222`: reads stack trace enablement with a deprecated fallback.
  - `dd-java-agent/appsec/src/main/java/com/datadog/appsec/ddwaf/WAFModule.java:385-392`: the WAF `generate_stack` action is only processed when `Config.get().isAppSecStackTraceEnabled()` is true.
- **Inference**: Enables/disables collection of exploit stack traces triggered by WAF actions.

### `DD_APPSEC_WAF_METRICS` (A)

- **Mapping**: `DD_APPSEC_WAF_METRICS` ↔ `AppSecConfig.APPSEC_WAF_METRICS` (`"appsec.waf.metrics"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2200`: reads `appsec.waf.metrics`.
  - `dd-java-agent/appsec/src/main/java/com/datadog/appsec/config/AppSecConfigServiceImpl.java:133-135`: adds `WAFStatsReporter` only when enabled.
  - `dd-java-agent/appsec/src/main/java/com/datadog/appsec/ddwaf/WAFModule.java:133-135`: caches `Config.get().isAppSecWafMetrics()` for use in WAF module behavior.
- **Inference**: Enables/disables WAF metrics reporting for AppSec.

### `DD_APP_CUSTOMJMXBUILDER` (A)

- **Mapping**: `DD_APP_CUSTOMJMXBUILDER` ↔ sysprop `dd.app.customjmxbuilder` (and env-var equivalent via `DD_APP_CUSTOMJMXBUILDER`).
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:1687-1708`: detects/forces “custom JMX builder” via `dd.app.customjmxbuilder` or `javax.management.builder.initial`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:363-373`: when a custom JMX builder is detected, JMXFetch initialization is delayed.
- **Inference**: Used to avoid breaking applications that install a custom `MBeanServerBuilder` by delaying JMX-dependent startup work; can be set explicitly to override detection.

### `DD_APP_CUSTOMLOGMANAGER` (A)

- **Mapping**: `DD_APP_CUSTOMLOGMANAGER` ↔ sysprop `dd.app.customlogmanager` (and env-var equivalent via `DD_APP_CUSTOMLOGMANAGER`).
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:1655-1676`: detects/forces “custom log manager” via `dd.app.customlogmanager` or `java.util.logging.manager`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:368-397`: when a custom log manager is detected, JMXFetch (and in some cases tracer/profiling init) is delayed to avoid initializing JUL too early.
- **Inference**: Used to avoid preventing the app from installing its custom JUL `LogManager` by delaying initialization that would otherwise load JUL too early; can be set explicitly to override detection.

### `DD_AZURE_APP_SERVICES` (B)

- **Mapping**: `DD_AZURE_APP_SERVICES` ↔ `GeneralConfig.AZURE_APP_SERVICES` (`"azure.app.services"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2756`: reads the `azure.app.services` flag.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:4802-4804` and `4908-4910`: when enabled, Azure App Services tags (`aas.*`) are added to local root span and profiling tags.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5071-5073`: Azure App Services tags include the site extension version (from `DD_AAS_JAVA_EXTENSION_VERSION`).
- **Inference**: Enables Azure App Services environment tagging for spans/profiling.

### `DD_CIVISIBILITY_ADDITIONAL_CHILD_PROCESS_JVM_ARGS` (A)

- **Mapping**: `DD_CIVISIBILITY_ADDITIONAL_CHILD_PROCESS_JVM_ARGS` ↔ `CiVisibilityConfig.CIVISIBILITY_ADDITIONAL_CHILD_PROCESS_JVM_ARGS` (`"civisibility.additional.child.process.jvm.args"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2364-2365`: reads the string.
  - `dd-java-agent/instrumentation/maven/maven-3.2.1/src/main/java/datadog/trace/instrumentation/maven3/MavenProjectConfigurator.java:76-79`: appends the string to the child JVM `argLine`.
  - `dd-java-agent/instrumentation/gradle/gradle-8.3/src/main/groovy/datadog/trace/instrumentation/gradle/CiVisibilityService.java:88-92`: splits and appends args to forked JVM args.
- **Inference**: Provides additional JVM args to propagate into forked test JVMs when CI Visibility auto-injects the tracer.

### `DD_CIVISIBILITY_AGENTLESS_URL` (A)

- **Mapping**: `DD_CIVISIBILITY_AGENTLESS_URL` ↔ `CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL` (`"civisibility.agentless.url"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2350-2352`: reads and validates the URL.
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/WriterFactory.java:217-220`: uses it as the host URL for CI Visibility intake in agentless mode.
  - `telemetry/src/main/java/datadog/telemetry/TelemetryClient.java:55-59`: uses it as the base for `/api/v2/apmtelemetry` in agentless mode.
- **Inference**: Overrides the agentless intake host URL for CI Visibility traces/coverage and telemetry.

### `DD_CIVISIBILITY_AGENT_JAR_URI` (A)

- **Mapping**: `DD_CIVISIBILITY_AGENT_JAR_URI` ↔ `CiVisibilityConfig.CIVISIBILITY_AGENT_JAR_URI` (`"civisibility.agent.jar.uri"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3972-3982`: converts the configured URI to a local `File` for use as `-javaagent`.
  - `dd-java-agent/instrumentation/maven/maven-3.2.1/src/main/java/datadog/trace/instrumentation/maven3/MavenProjectConfigurator.java:81-85`: uses the file path to add `-javaagent:<path>` to forked processes.
- **Inference**: Provides the location (URI) of the Java tracer `-javaagent` jar used for CI Visibility injection into child JVMs.

### `DD_CIVISIBILITY_AUTO_CONFIGURATION_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_AUTO_CONFIGURATION_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_AUTO_CONFIGURATION_ENABLED` (`"civisibility.auto.configuration.enabled"`).
- **Evidence**:
  - `dd-java-agent/instrumentation/maven/maven-3.2.1/src/main/java/datadog/trace/instrumentation/maven3/MavenProjectConfigurator.java:54-56`: disables Maven auto-configuration when false.
  - `dd-java-agent/instrumentation/gradle/gradle-3.0/src/main/groovy/datadog/trace/instrumentation/gradle/legacy/GradleProjectConfigurator.groovy:49-51`: disables Gradle auto-configuration when false.
- **Inference**: Master toggle for CI Visibility build auto-configuration (injection/configuration of forked test JVMs).

### `DD_CIVISIBILITY_AUTO_INSTRUMENTATION_PROVIDER` (A)

- **Mapping**: `DD_CIVISIBILITY_AUTO_INSTRUMENTATION_PROVIDER` ↔ `CiVisibilityConfig.CIVISIBILITY_AUTO_INSTRUMENTATION_PROVIDER` (`"civisibility.auto.instrumentation.provider"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2473-2474`: considers CI Visibility “auto injected” when this string is non-blank.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/domain/AbstractTestSession.java:132-137`: uses `config.isCiVisibilityAutoInjected()` to add the `AutoInjected.TRUE` telemetry tag.
- **Inference**: Marks CI Visibility sessions as auto-injected for telemetry when this value is set (non-empty).

### `DD_CIVISIBILITY_BACKEND_API_TIMEOUT_MILLIS` (A)

- **Mapping**: `DD_CIVISIBILITY_BACKEND_API_TIMEOUT_MILLIS` ↔ `CiVisibilityConfig.CIVISIBILITY_BACKEND_API_TIMEOUT_MILLIS` (`"civisibility.backend.api.timeout.millis"`).
- **Evidence**:
  - `communication/src/main/java/datadog/communication/ddagent/SharedCommunicationObjects.java:72-75`: uses it as the shared HTTP client timeout when CI Visibility is enabled.
- **Inference**: Controls network timeouts for CI Visibility backend communications.

### `DD_CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED` (`"civisibility.build.instrumentation.enabled"`).
- **Evidence**:
  - `dd-java-agent/instrumentation/maven/maven-3.2.1/src/main/java/datadog/trace/instrumentation/maven3/MavenInstrumentation.java:47-49`: Maven build instrumentation is gated by this config.
  - `dd-java-agent/instrumentation/gradle/gradle-8.3/src/main/groovy/datadog/trace/instrumentation/gradle/GradlePluginInjectorInstrumentation.java:42-45`: Gradle build instrumentation is gated by this config.
- **Inference**: Enables/disables CI Visibility build-system instrumentation modules.

### `DD_CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED` (`"civisibility.ciprovider.integration.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ci/CIProviderInfoFactory.java:23-26`: when disabled, CI provider info is forced to “unknown”.
- **Inference**: Enables/disables detection of the CI provider and provider-specific environment variable parsing.

### `DD_CIVISIBILITY_CODE_COVERAGE_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_CODE_COVERAGE_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_ENABLED` (`"civisibility.code.coverage.enabled"`).
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/WriterFactory.java:117-121`: when enabled, adds the `CITESTCOV` intake track.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/domain/buildsystem/BuildSystemSessionImpl.java:111-115`: when disabled, coverage include list becomes empty.
- **Inference**: Master toggle for CI Visibility per-test code coverage collection/submission.

### `DD_CIVISIBILITY_CODE_COVERAGE_EXCLUDES` (A)

- **Mapping**: `DD_CIVISIBILITY_CODE_COVERAGE_EXCLUDES` ↔ `CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_EXCLUDES` (`"civisibility.code.coverage.excludes"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2387-2392`: reads the value and splits it on `:` into a list.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/domain/buildsystem/BuildSystemSessionImpl.java:92-96`: passes excludes into `BuildSessionSettings`.
- **Inference**: Excludes packages from CI Visibility code coverage (using colon-separated patterns).

### `DD_CIVISIBILITY_CODE_COVERAGE_INCLUDES` (A)

- **Mapping**: `DD_CIVISIBILITY_CODE_COVERAGE_INCLUDES` ↔ `CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_INCLUDES` (`"civisibility.code.coverage.includes"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/domain/buildsystem/BuildSystemSessionImpl.java:117-123`: uses configured includes if present; otherwise uses repo-index root packages.
- **Inference**: Controls which packages are considered for coverage (overrides auto-derived root packages when configured).

### `DD_CIVISIBILITY_CODE_COVERAGE_LINES_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_CODE_COVERAGE_LINES_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_LINES_ENABLED` (`"civisibility.code.coverage.lines.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2372-2373` and `4004-4012`: tri-state setting (enabled/disabled if explicitly set).
  - `dd-java-agent/instrumentation/jacoco-0.8.9/src/main/java/datadog/trace/instrumentation/jacoco/ClassInstrumenterInstrumentation.java:25-27`: JaCoCo line/probe instrumentation is gated by this config.
- **Inference**: Enables/disables line-level (probe-based) coverage instrumentation used for CI Visibility per-test line granularity.

### `DD_CIVISIBILITY_CODE_COVERAGE_REPORT_DUMP_DIR` (A)

- **Mapping**: `DD_CIVISIBILITY_CODE_COVERAGE_REPORT_DUMP_DIR` ↔ `CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_REPORT_DUMP_DIR` (`"civisibility.code.coverage.report.dump.dir"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/coverage/report/JacocoCoverageProcessor.java:300-307`: when set, builds a folder path under the dump dir and writes reports there.
- **Inference**: If set, CI Visibility writes aggregated JaCoCo reports (HTML/XML) to the configured directory.

### `DD_CIVISIBILITY_CODE_COVERAGE_REPORT_UPLOAD_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_CODE_COVERAGE_REPORT_UPLOAD_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_REPORT_UPLOAD_ENABLED` (`"civisibility.code.coverage.report.upload.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/config/ExecutionSettingsFactoryImpl.java:182-186`: uses this as the local fallback when computing whether coverage report upload is enabled.
- **Inference**: Enables/disables uploading aggregated coverage reports (as opposed to only sending coverage events).

### `DD_CIVISIBILITY_CODE_COVERAGE_ROOT_PACKAGES_LIMIT` (A)

- **Mapping**: `DD_CIVISIBILITY_CODE_COVERAGE_ROOT_PACKAGES_LIMIT` ↔ `CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_ROOT_PACKAGES_LIMIT` (`"civisibility.code.coverage.root.packages.limit"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/source/index/PackageTree.java:36-57`: limits the number of retained root packages and truncates/coarsens if needed.
- **Inference**: Caps how many root packages CI Visibility keeps when auto-deriving coverage scope.

### `DD_CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED` (`"civisibility.compiler.plugin.auto.configuration.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2366-2369`: reads the boolean.
  - `dd-java-agent/instrumentation/maven/maven-3.2.1/src/main/java/datadog/trace/instrumentation/maven3/MavenProjectConfigurator.java:108-110`: skips compiler plugin auto-configuration when disabled.
  - `dd-java-agent/instrumentation/gradle/gradle-8.3/src/main/groovy/datadog/trace/instrumentation/gradle/CiVisibilityService.java:35-37`: Gradle CI Visibility service uses it to enable compiler plugin behavior.
- **Inference**: Master toggle for auto-configuring the Datadog javac compiler plugin in build tool integrations.

### `DD_CIVISIBILITY_COMPILER_PLUGIN_VERSION` (A)

- **Mapping**: `DD_CIVISIBILITY_COMPILER_PLUGIN_VERSION` ↔ `CiVisibilityConfig.CIVISIBILITY_COMPILER_PLUGIN_VERSION` (`"civisibility.compiler.plugin.version"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2376-2378`: reads the version string.
  - `dd-java-agent/instrumentation/gradle/gradle-8.3/src/main/groovy/datadog/trace/instrumentation/gradle/CiVisibilityPlugin.java:90-106`: builds detached dependencies `com.datadoghq:dd-javac-plugin:<version>` and `dd-javac-plugin-client:<version>`.
- **Inference**: Selects which dd-javac-plugin version is injected/configured by CI Visibility build integrations.

### `DD_CIVISIBILITY_DEBUG_PORT` (A)

- **Mapping**: `DD_CIVISIBILITY_DEBUG_PORT` ↔ `CiVisibilityConfig.CIVISIBILITY_DEBUG_PORT` (`"civisibility.debug.port"`).
- **Evidence**:
  - `dd-java-agent/instrumentation/maven/maven-3.2.1/src/main/java/datadog/trace/instrumentation/maven3/MavenProjectConfigurator.java:68-74`: adds JDWP `-agentlib:jdwp=...address=<port>` to child JVM argLine when set.
  - `dd-java-agent/instrumentation/gradle/gradle-8.3/src/main/groovy/datadog/trace/instrumentation/gradle/CiVisibilityService.java:82-86`: adds JDWP agentlib argument to forked JVMs when set.
- **Inference**: Enables debugging of CI Visibility-instrumented child JVMs by adding a JDWP debug agent on the configured port.

### `DD_CIVISIBILITY_EARLY_FLAKE_DETECTION_ENABLED` (B)

- **Mapping**: `DD_CIVISIBILITY_EARLY_FLAKE_DETECTION_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_EARLY_FLAKE_DETECTION_ENABLED` (`"civisibility.early.flake.detection.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2460-2462`: reads the boolean.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/test/ExecutionStrategy.java:135-143`: enables running tests multiple times for EFD when applicable.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ipc/ModuleExecutionResult.java:148-176`: propagates EFD-enabled flag in module execution results.
- **Inference**: Enables/disables Early Flake Detection behavior in CI Visibility.

### `DD_CIVISIBILITY_EARLY_FLAKE_DETECTION_LOWER_LIMIT` (A)

- **Mapping**: `DD_CIVISIBILITY_EARLY_FLAKE_DETECTION_LOWER_LIMIT` ↔ `CiVisibilityConfig.CIVISIBILITY_EARLY_FLAKE_DETECTION_LOWER_LIMIT` (`"civisibility.early.flake.detection.lower.limit"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2463`: reads the integer (default 30).
  - Propagated into child/system settings via `BuildSystemModuleImpl` (see `Config.java` section where CI Visibility settings are read and propagated).
- **Inference**: Threshold parameter for Early Flake Detection (lower bound), used in CI Visibility execution settings/thresholding.

### `DD_CIVISIBILITY_EXECUTION_SETTINGS_CACHE_SIZE` (A)

- **Mapping**: `DD_CIVISIBILITY_EXECUTION_SETTINGS_CACHE_SIZE` ↔ `CiVisibilityConfig.CIVISIBILITY_EXECUTION_SETTINGS_CACHE_SIZE` (`"civisibility.execution.settings.cache.size"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/config/MultiModuleExecutionSettingsFactory.java:18-21`: fixed-size cache for settings by JVM.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/config/CachingJvmInfoFactory.java:15-18`: fixed-size cache for JVM info by executable path.
- **Inference**: Controls cache size used to reduce overhead when repeatedly computing execution settings/JVM info.

### `DD_CIVISIBILITY_FLAKY_RETRY_ONLY_KNOWN_FLAKES` (A)

- **Mapping**: `DD_CIVISIBILITY_FLAKY_RETRY_ONLY_KNOWN_FLAKES` ↔ `CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_ONLY_KNOWN_FLAKES` (`"civisibility.flaky.retry.only.known.flakes"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/config/ExecutionSettingsFactoryImpl.java:380-386`: determines whether to request flaky-tests data.
- **Inference**: When enabled, only requests/uses flaky-tests data in the “known flakes” mode (affects whether flaky-tests list is fetched/used).

### `DD_CIVISIBILITY_GIT_CLIENT_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_GIT_CLIENT_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_GIT_CLIENT_ENABLED` (`"civisibility.git.client.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilityServices.java:121-129`: disables git client (uses `NoOpGitClient`) when false.
- **Inference**: Enables/disables collecting Git metadata via shelling out to `git`.

### `DD_CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS` (A)

- **Mapping**: `DD_CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS` ↔ `CiVisibilityConfig.CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS` (`"civisibility.git.command.timeout.millis"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/git/tree/ShellGitClient.java:1009-1015`: passed into `ShellGitClient` as command timeout.
- **Inference**: Sets how long CI Visibility will wait for individual `git` commands when using the shell git client.

### `DD_CIVISIBILITY_GIT_REMOTE_NAME` (A)

- **Mapping**: `DD_CIVISIBILITY_GIT_REMOTE_NAME` ↔ `CiVisibilityConfig.CIVISIBILITY_GIT_REMOTE_NAME` (`"civisibility.git.remote.name"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/git/GitClientGitInfoBuilder.java:35-37`: uses it to resolve the remote URL.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilityRepoServices.java:288-298`: used for Git data upload setup.
- **Inference**: Selects which git remote is used for repo URL/Git data upload (default `origin`).

### `DD_CIVISIBILITY_GIT_UNSHALLOW_DEFER` (A)

- **Mapping**: `DD_CIVISIBILITY_GIT_UNSHALLOW_DEFER` ↔ `CiVisibilityConfig.CIVISIBILITY_GIT_UNSHALLOW_DEFER` (`"civisibility.git.unshallow.defer"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/git/tree/GitDataUploaderImpl.java:93-118`: controls whether unshallow happens early or only when needed.
- **Inference**: Controls whether the tracer defers unshallowing shallow git clones until required for Git data upload.

### `DD_CIVISIBILITY_GIT_UPLOAD_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_GIT_UPLOAD_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_GIT_UPLOAD_ENABLED` (`"civisibility.git.upload.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilityRepoServices.java:272-275`: when disabled, Git data uploader is a no-op.
- **Inference**: Enables/disables uploading git tree/pack data for CI Visibility.

### `DD_CIVISIBILITY_GIT_UPLOAD_TIMEOUT_MILLIS` (A)

- **Mapping**: `DD_CIVISIBILITY_GIT_UPLOAD_TIMEOUT_MILLIS` ↔ `CiVisibilityConfig.CIVISIBILITY_GIT_UPLOAD_TIMEOUT_MILLIS` (`"civisibility.git.upload.timeout.millis"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/git/tree/GitDataUploaderImpl.java:183-191`: timeout while waiting for upload completion.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/config/ExecutionSettingsFactoryImpl.java:349-352`: waits for upload to finish before requesting skippable tests/settings.
- **Inference**: Sets how long CI Visibility will wait for git data upload to complete before continuing.

### `DD_CIVISIBILITY_GRADLE_SOURCESETS` (A)

- **Mapping**: `DD_CIVISIBILITY_GRADLE_SOURCESETS` ↔ `CiVisibilityConfig.CIVISIBILITY_GRADLE_SOURCE_SETS` (`"civisibility.gradle.sourcesets"`).
- **Evidence**:
  - `dd-java-agent/instrumentation/gradle/gradle-8.3/src/main/groovy/datadog/trace/instrumentation/gradle/CiVisibilityService.java:56-58`: used to select source sets for module layout/coverage.
  - `dd-java-agent/instrumentation/gradle/gradle-8.3/src/main/groovy/datadog/trace/instrumentation/gradle/CiVisibilityPlugin.java:57-76`: iterates selected source sets to build module layout.
- **Inference**: Controls which Gradle source sets CI Visibility uses for coverage/module layout (defaults to `main,test`).

### `DD_CIVISIBILITY_IMPACTED_TESTS_DETECTION_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_IMPACTED_TESTS_DETECTION_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_IMPACTED_TESTS_DETECTION_ENABLED` (`"civisibility.impacted.tests.detection.enabled"`).
- **Evidence**:
  - Propagated as part of module/system properties (see `BuildSystemModuleImpl` settings propagation).
- **Inference**: Kill-switch for impacted tests detection (TIA) in CI Visibility.

### `DD_CIVISIBILITY_INJECTED_TRACER_VERSION` (A)

- **Mapping**: `DD_CIVISIBILITY_INJECTED_TRACER_VERSION` ↔ `CiVisibilityConfig.CIVISIBILITY_INJECTED_TRACER_VERSION` (`"civisibility.injected.tracer.version"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilitySystem.java:65-74`: detects mismatch between injected version and current tracer version.
  - `dd-java-agent/instrumentation/gradle/gradle-8.3/src/main/groovy/datadog/trace/instrumentation/gradle/GradleDaemonInjectionUtils.java:17-24`: skips nested Gradle daemon injection when this property is already set.
- **Inference**: Used to mark/validate auto-injection across parent/child JVMs and prevent misconfiguration/nested injection.

### `DD_CIVISIBILITY_INTAKE_AGENTLESS_URL` (A)

- **Mapping**: `DD_CIVISIBILITY_INTAKE_AGENTLESS_URL` ↔ `CiVisibilityConfig.CIVISIBILITY_INTAKE_AGENTLESS_URL` (`"civisibility.intake.agentless.url"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/intake/Intake.java:14-18,53-60`: when set, overrides CI intake agentless URL used to build `.../api/v2/`.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilityServices.java:84`: used to create backend API client for `Intake.CI_INTAKE`.
- **Inference**: Overrides the CI intake endpoint base URL used for agentless submission.

### `DD_CIVISIBILITY_JACOCO_PLUGIN_VERSION` (A)

- **Mapping**: `DD_CIVISIBILITY_JACOCO_PLUGIN_VERSION` ↔ `CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_VERSION` (`"civisibility.jacoco.plugin.version"`).
- **Evidence**:
  - `dd-java-agent/instrumentation/maven/maven-3.2.1/src/main/java/datadog/trace/instrumentation/maven3/MavenProjectConfigurator.java:333-349`: sets injected `jacoco-maven-plugin` version to this value.
  - `dd-java-agent/instrumentation/gradle/gradle-8.3/src/main/groovy/datadog/trace/instrumentation/gradle/CiVisibilityPlugin.java:153-156`: sets Gradle JaCoCo tool version.
- **Inference**: Selects which JaCoCo version CI Visibility uses when injecting/configuring coverage tooling.

### `DD_CIVISIBILITY_JVM_INFO_CACHE_SIZE` (A)

- **Mapping**: `DD_CIVISIBILITY_JVM_INFO_CACHE_SIZE` ↔ `CiVisibilityConfig.CIVISIBILITY_JVM_INFO_CACHE_SIZE` (`"civisibility.jvm.info.cache.size"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2444-2445`: reads the value.
  - (No usage found in code; current CI Visibility JVM info caching uses `DD_CIVISIBILITY_EXECUTION_SETTINGS_CACHE_SIZE` in `CachingJvmInfoFactory`.)
- **Inference**: Intended to control JVM info caching size, but no runtime usage found; description is based on config name and nearby usage patterns.

### `DD_CIVISIBILITY_KNOWN_TESTS_REQUEST_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_KNOWN_TESTS_REQUEST_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_KNOWN_TESTS_REQUEST_ENABLED` (`"civisibility.known.tests.request.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/config/ExecutionSettingsFactoryImpl.java:177-182,396-407`: controls requesting known tests by module.
- **Inference**: Enables/disables requesting “known tests” data from the backend for CI Visibility execution settings.

### `DD_CIVISIBILITY_MODULE_NAME` (A)

- **Mapping**: `DD_CIVISIBILITY_MODULE_NAME` ↔ `CiVisibilityConfig.CIVISIBILITY_MODULE_NAME` (`"civisibility.module.name"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilityRepoServices.java:198-211`: uses `config.getCiVisibilityModuleName()` (propagated from an instrumented parent build process) as the module name; otherwise derives it from repo-relative path or falls back to service name.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/domain/buildsystem/BuildSystemModuleImpl.java:201-205`: propagates the module name to child processes via system properties (`dd.civisibility.module.name`).
- **Inference**: Sets the CI Visibility “module name” used for module-scoped execution settings and tagging, especially in forked/child JVMs.

### `DD_CIVISIBILITY_REMOTE_ENV_VARS_PROVIDER_KEY` (A)

- **Mapping**: `DD_CIVISIBILITY_REMOTE_ENV_VARS_PROVIDER_KEY` ↔ system property `dd.civisibility.remote.env.vars.provider.key` (read by `CiEnvironmentVariables`, which also supports the env-var form via `ConfigStrings.toEnvVar(...)`).
- **Evidence**:
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/civisibility/CiEnvironmentVariables.java:40-47`: if both provider URL and key are set, loads a remote environment map at startup.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/civisibility/CiEnvironmentVariables.java:109-110`: sends the key as `DD-Env-Vars-Provider-Key` header and requests `text/plain`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:514-532`: remote environment is inserted as a config source (treated like env vars) when present.
- **Inference**: Authentication/selector key used when fetching remote environment variables for CI Visibility.

### `DD_CIVISIBILITY_REMOTE_ENV_VARS_PROVIDER_URL` (A)

- **Mapping**: `DD_CIVISIBILITY_REMOTE_ENV_VARS_PROVIDER_URL` ↔ system property `dd.civisibility.remote.env.vars.provider.url` (read by `CiEnvironmentVariables`, with env-var fallback).
- **Evidence**:
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/civisibility/CiEnvironmentVariables.java:40-47`: URL + key enable remote environment fetching with retries.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/civisibility/CiEnvironmentVariables.java:106-120`: performs an HTTP GET to the URL and loads the response as Java `Properties`.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilityServices.java:142-150`: remote environment is used for CI environment (CI provider) detection when present.
- **Inference**: URL of a remote “environment variables provider” endpoint; the returned properties (env-var keys) are used as an additional environment/config source for CI Visibility.

### `DD_CIVISIBILITY_REPO_INDEX_DUPLICATE_KEY_CHECK_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_REPO_INDEX_DUPLICATE_KEY_CHECK_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_REPO_INDEX_DUPLICATE_KEY_CHECK_ENABLED` (`"civisibility.repo.index.duplicate.key.check.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/source/index/RepoIndex.java:101-105`: when enabled, throws if a lookup key is duplicated in the repo index.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2439`: default is `true`.
- **Inference**: Guardrail for CI Visibility source path resolution: fail fast on ambiguous/duplicate repo-index keys when enabled.

### `DD_CIVISIBILITY_REPO_INDEX_FOLLOW_SYMLINKS` (A)

- **Mapping**: `DD_CIVISIBILITY_REPO_INDEX_FOLLOW_SYMLINKS` ↔ `CiVisibilityConfig.CIVISIBILITY_REPO_INDEX_FOLLOW_SYMLINKS` (`"civisibility.repo.index.follow.symlinks"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/source/index/RepoIndexBuilder.java:124,128-143`: when disabled, symlink directories are skipped; when enabled, symlinks may be traversed with special-casing to avoid duplicate results for links pointing inside the repo.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2441`: default is `false`.
- **Inference**: Controls whether CI Visibility’s repository indexer traverses symbolic links.

### `DD_CIVISIBILITY_RESOURCE_FOLDER_NAMES` (A)

- **Mapping**: `DD_CIVISIBILITY_RESOURCE_FOLDER_NAMES` ↔ `CiVisibilityConfig.CIVISIBILITY_RESOURCE_FOLDER_NAMES` (`"civisibility.resource.folder.names"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/source/index/ConventionBasedResourceResolver.java:17-38`: uses the configured list to find a resource root by substring match in the resource file path.
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:193-195`: default list is `["/resources/", "/java/", "/groovy/", "/kotlin/", "/scala/"]`.
- **Inference**: Configures which path segments are treated as “resource folders” when CI Visibility tries to resolve resource roots.

### `DD_CIVISIBILITY_RUM_FLUSH_WAIT_MILLIS` (A)

- **Mapping**: `DD_CIVISIBILITY_RUM_FLUSH_WAIT_MILLIS` ↔ `CiVisibilityConfig.CIVISIBILITY_RUM_FLUSH_WAIT_MILLIS` (`"civisibility.rum.flush.wait.millis"`).
- **Evidence**:
  - `dd-java-agent/instrumentation/selenium-3.13/src/main/java/datadog/trace/instrumentation/selenium/SeleniumUtils.java:193-199`: sleeps for `Config.get().getCiVisibilityRumFlushWaitMillis()` after calling `DD_RUM.stopSession()`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2472`: default is `500` ms.
- **Inference**: Delay (ms) to wait after stopping a RUM session in Selenium browser tests, to allow RUM to flush.

### `DD_CIVISIBILITY_SCALATEST_FORK_MONITOR_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_SCALATEST_FORK_MONITOR_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_SCALATEST_FORK_MONITOR_ENABLED` (`"civisibility.scalatest.fork.monitor.enabled"`).
- **Evidence**:
  - `dd-java-agent/instrumentation/scalatest-3.0.8/src/main/java/datadog/trace/instrumentation/scalatest/ScalatestForkInstrumentation.java:45-62`: explains this is an opt-in instrumentation for SBT `Test / fork` to prevent double reporting by disabling tracing in the parent process.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2480`: default is `false`.
- **Inference**: Opt-in fork-monitor mode for Scalatest/SBT that avoids double-reporting when tests are forked.

### `DD_CIVISIBILITY_SIGNAL_CLIENT_TIMEOUT_MILLIS` (A)

- **Mapping**: `DD_CIVISIBILITY_SIGNAL_CLIENT_TIMEOUT_MILLIS` ↔ `CiVisibilityConfig.CIVISIBILITY_SIGNAL_CLIENT_TIMEOUT_MILLIS` (`"civisibility.signal.client.timeout.millis"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ipc/SignalClient.java:47-49`: uses the value for `Socket#setSoTimeout` and connect timeout.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ipc/SignalClient.java:136-138`: factory passes `config.getCiVisibilitySignalClientTimeoutMillis()`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2432`: default is `10_000` ms.
- **Inference**: Timeout (ms) for CI Visibility IPC traffic between child JVMs and the parent signal server.

### `DD_CIVISIBILITY_SIGNAL_SERVER_HOST` (A)

- **Mapping**: `DD_CIVISIBILITY_SIGNAL_SERVER_HOST` ↔ `CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_HOST` (`"civisibility.signal.server.host"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ipc/SignalServer.java:52`: binds the server socket to the configured host.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ProcessHierarchy.java:96-108`: child processes read the host/port from system properties to connect to the parent.
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:191`: default is `127.0.0.1`.
- **Inference**: Controls the bind address for the CI Visibility IPC signal server (and the host children connect to).

### `DD_CIVISIBILITY_SIGNAL_SERVER_PORT` (A)

- **Mapping**: `DD_CIVISIBILITY_SIGNAL_SERVER_PORT` ↔ `CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_PORT` (`"civisibility.signal.server.port"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ipc/SignalServer.java:52`: binds the server socket to the configured port (0 ⇒ ephemeral).
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ProcessHierarchy.java:96-108`: children read the port from system properties to connect back.
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:192`: default is `0`.
- **Inference**: Controls the port for the CI Visibility IPC signal server.

### `DD_CIVISIBILITY_SOURCE_DATA_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_SOURCE_DATA_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_SOURCE_DATA_ENABLED` (`"civisibility.source.data.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/domain/TestSuiteImpl.java:128-130`: when enabled, populates source-file/lines/codeowners tags for suite spans.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/domain/TestImpl.java:145-148`: similarly populates source metadata for test spans.
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:177`: default is `true`.
- **Inference**: Enables attaching test source metadata (path/lines/CODEOWNERS) to CI Visibility test spans.

### `DD_CIVISIBILITY_TELEMETRY_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_TELEMETRY_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_TELEMETRY_ENABLED` (`"civisibility.telemetry.enabled"`).
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:846-848`: adds `CiVisibilityTelemetryInterceptor` when enabled.
  - `telemetry/src/main/java/datadog/telemetry/TelemetrySystem.java:65-67`: adds `CiVisibilityMetricPeriodicAction` when CI Visibility is enabled and CI Visibility telemetry is enabled.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2470`: default is `true`.
- **Inference**: Enables CI Visibility telemetry reporting (metrics + event counting via a trace interceptor).

### `DD_CIVISIBILITY_TEST_COMMAND` (A)

- **Mapping**: `DD_CIVISIBILITY_TEST_COMMAND` ↔ `CiVisibilityConfig.CIVISIBILITY_TEST_COMMAND` (`"civisibility.test.command"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilitySystem.java:265-267`: child sessions read `config.getCiVisibilityTestCommand()` and pass it to `TestDecoratorImpl`.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/decorator/TestDecoratorImpl.java:20-31`: uses the command to build the test session name when no explicit session name is configured.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/domain/buildsystem/BuildSystemModuleImpl.java:203-206`: propagated to child processes as a system property.
- **Inference**: Propagates the “test command” string into child JVMs and uses it to derive CI Visibility session naming.

### `DD_CIVISIBILITY_TEST_ORDER` (A)

- **Mapping**: `DD_CIVISIBILITY_TEST_ORDER` ↔ `CiVisibilityConfig.CIVISIBILITY_TEST_ORDER` (`"civisibility.test.order"`).
- **Evidence**:
  - `dd-java-agent/instrumentation/junit/junit-5/junit-5.8/src/main/java/datadog/trace/instrumentation/junit5/order/JUnit5TestOrderInstrumentation.java:84-93,110-119`: supports `FAILFAST` and installs fail-fast class/method orderers; unknown values throw `IllegalArgumentException`.
- **Inference**: Enables/configures test ordering instrumentation; currently `FAILFAST` is the supported mode.

### `DD_CIVISIBILITY_TEST_SKIPPING_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_TEST_SKIPPING_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_TEST_SKIPPING_ENABLED` (`"civisibility.test.skipping.enabled"`).
- **Evidence**:
  - `dd-java-agent/instrumentation/junit/junit-4/junit-4.10/src/main/java/datadog/trace/instrumentation/junit4/JUnit4SkipInstrumentation.java:38-42,95-117`: enables skipping instrumentation and marks tests as ignored when a backend-provided `SkipReason` is present (with extra checks for ITR “unskippable” tags).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2435`: default is `true`.
- **Inference**: Controls whether CI Visibility can automatically skip tests (for example via ITR) in instrumented test frameworks.

### `DD_CIVISIBILITY_TOTAL_FLAKY_RETRY_COUNT` (A)

- **Mapping**: `DD_CIVISIBILITY_TOTAL_FLAKY_RETRY_COUNT` ↔ `CiVisibilityConfig.CIVISIBILITY_TOTAL_FLAKY_RETRY_COUNT` (`"civisibility.total.flaky.retry.count"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/test/ExecutionStrategy.java:159-167`: caps auto-retry applicability based on `autoRetriesUsed < config.getCiVisibilityTotalFlakyRetryCount()`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2466`: default is `1000`.
- **Inference**: Global cap on how many flaky-test retries CI Visibility will perform across the entire test session.

### `DD_CIVISIBILITY_TRACE_SANITATION_ENABLED` (A)

- **Mapping**: `DD_CIVISIBILITY_TRACE_SANITATION_ENABLED` ↔ `CiVisibilityConfig.CIVISIBILITY_TRACE_SANITATION_ENABLED` (`"civisibility.trace.sanitation.enabled"`).
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:831-834`: installs `CiVisibilityTraceInterceptor` only when enabled.
  - `dd-trace-core/src/main/java/datadog/trace/civisibility/interceptor/CiVisibilityTraceInterceptor.java:34-43`: drops traces whose root origin is not `ciapp-test`, and sets `library_version` for CI traces.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2335`: default is `true`.
- **Inference**: “Sanitizes” CI Visibility mode by filtering out non-CI traces and tagging CI traces consistently.

### `DD_CODE_ORIGIN_FOR_SPANS_ENABLED` (B)

- **Mapping**: `DD_CODE_ORIGIN_FOR_SPANS_ENABLED` ↔ `TraceInstrumentationConfig.CODE_ORIGIN_FOR_SPANS_ENABLED` (`"code.origin.for.spans.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2601-2603`: reads the setting into `debuggerCodeOriginEnabled` (`Config.isDebuggerCodeOriginEnabled()`).
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerAgent.java:86-96,304-316`: when enabled, starts “Code Origin for spans” and installs a `DefaultCodeOriginRecorder` via `DebuggerContext.initCodeOrigin(...)`.
  - `dd-java-agent/instrumentation/datadog/dynamic-instrumentation/span-origin/src/main/java/datadog/trace/instrumentation/codeorigin/CodeOriginInstrumentation.java:27-48` and `EntrySpanOriginAdvice.java:10-18`: gated by `InstrumenterConfig.get().isCodeOriginEnabled()` and calls `DebuggerContext.captureCodeOrigin(...)` on method entry.
- **Inference**: Enables “Code Origin for spans” (Dynamic Instrumentation), which captures code origin for entry spans and links it to spans via code-origin probe/snapshot machinery.

### `DD_CODE_ORIGIN_MAX_USER_FRAMES` (A)

- **Mapping**: `DD_CODE_ORIGIN_MAX_USER_FRAMES` ↔ `TraceInstrumentationConfig.CODE_ORIGIN_MAX_USER_FRAMES` (`"code.origin.max.user.frames"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2604-2605`: reads the value into `debuggerCodeOriginMaxUserFrames`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/codeorigin/DefaultCodeOriginRecorder.java:56`: reads it into a `maxUserFrames` field, but the field is not referenced elsewhere in the recorder.
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:54`: default is `8`.
- **Inference**: Intended to limit how many “user” frames are captured/considered for code origin recording; current implementation appears to read but not apply it (so it may have no effect).

### `DD_CRASHTRACKING_AGENTLESS` (A)

- **Mapping**: `DD_CRASHTRACKING_AGENTLESS` ↔ `CrashTrackingConfig.CRASH_TRACKING_AGENTLESS` (`"crashtracking.agentless"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2141-2143`: reads the boolean into `crashTrackingAgentless`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5137-5155`: when enabled, crash tracking sends directly to Datadog intake (`/api/v2/apmtelemetry` + `error-tracking-intake.../errorsintake`); otherwise it uses local Datadog Agent proxy endpoints (`/telemetry/proxy/...` + `/evp_proxy/...`).
  - `dd-trace-api/src/main/java/datadog/trace/api/config/CrashTrackingConfig.java:24-25`: default is `false` and is “not intended for production use”.
- **Inference**: Switches Crash Tracking uploads from “via local Datadog Agent” to direct agentless intake.

### `DD_CRASHTRACKING_DEBUG_AUTOCONFIG_ENABLE` (A)

- **Mapping**: `DD_CRASHTRACKING_DEBUG_AUTOCONFIG_ENABLE` ↔ `CrashTrackingConfig.CRASH_TRACKING_ENABLE_AUTOCONFIG` (`"crashtracking.debug.autoconfig.enable"`).
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:1269-1276`: if the property is not set, autoconfig defaults to whether profiling is enabled.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:1288-1300`: when autoconfig is enabled, crash tracking tries native init first; if it fails, initialization is delayed until JMX is available.
  - `dd-java-agent/agent-crashtracking/src/main/java/datadog/crashtracking/Initializer.java:77-101`: native init uses ddprof’s JVM-access library to initialize crash uploader + OOME notifier; if the library can’t be loaded it returns `false` and crash tracking relies on user-provided JVM args.
- **Inference**: Debug/advanced knob controlling whether Crash Tracking attempts to auto-configure JVM hooks using the native JVM-access library (otherwise it may defer and/or rely on preconfigured `-XX:` flags).

### `DD_CRASHTRACKING_DEBUG_START_FORCE_FIRST` (A)

- **Mapping**: `DD_CRASHTRACKING_DEBUG_START_FORCE_FIRST` ↔ `CrashTrackingConfig.CRASH_TRACKING_START_EARLY` (`"crashtracking.debug.start-force-first"`).
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:852-867`: when enabled, runs crash tracking initialization immediately; otherwise schedules it asynchronously (comment notes native init can take 100ms+).
- **Inference**: Debug/advanced knob to force Crash Tracking to initialize synchronously/early.

### `DD_CRASHTRACKING_ENABLED` (A)

- **Mapping**: `DD_CRASHTRACKING_ENABLED` ↔ `CrashTrackingConfig.CRASH_TRACKING_ENABLED` (`"crashtracking.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/CrashTrackingConfig.java:10-12`: Crash Tracking is enabled by default (`true`).
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:267,331-337`: when enabled, calls `startCrashTracking()`.
  - `dd-java-agent/agent-crashtracking/src/main/java/datadog/crashtracking/Initializer.java:205-278`: sets/augments JVM `OnError` and `OnOutOfMemoryError` commands to include Datadog scripts (`dd_crash_uploader`, `dd_oome_notifier`) and initializes those scripts.
- **Inference**: Master toggle for Crash Tracking initialization and its JVM crash/OOM hooks.

### `DD_CRASHTRACKING_PROXY_HOST` (A)

- **Mapping**: `DD_CRASHTRACKING_PROXY_HOST` ↔ `CrashTrackingConfig.CRASH_TRACKING_PROXY_HOST` (`"crashtracking.proxy.host"`).
- **Evidence**:
  - `dd-java-agent/agent-crashtracking/src/main/java/datadog/crashtracking/CrashUploader.java:167-178`: proxy host is passed to the OkHttp client builder used for crash uploads.
- **Inference**: Configures the HTTP proxy host for Crash Tracking uploads.

### `DD_CRASHTRACKING_PROXY_PORT` (A)

- **Mapping**: `DD_CRASHTRACKING_PROXY_PORT` ↔ `CrashTrackingConfig.CRASH_TRACKING_PROXY_PORT` (`"crashtracking.proxy.port"`).
- **Evidence**:
  - `dd-java-agent/agent-crashtracking/src/main/java/datadog/crashtracking/CrashUploader.java:167-178`: proxy port is passed to the OkHttp client builder used for crash uploads.
- **Inference**: Configures the HTTP proxy port for Crash Tracking uploads.

### `DD_CRASHTRACKING_PROXY_USERNAME` (A)

- **Mapping**: `DD_CRASHTRACKING_PROXY_USERNAME` ↔ `CrashTrackingConfig.CRASH_TRACKING_PROXY_USERNAME` (`"crashtracking.proxy.username"`).
- **Evidence**:
  - `dd-java-agent/agent-crashtracking/src/main/java/datadog/crashtracking/CrashUploader.java:167-178`: proxy username is passed to the OkHttp client builder used for crash uploads.
- **Inference**: Configures the HTTP proxy username (authentication) for Crash Tracking uploads.

### `DD_CRASHTRACKING_PROXY_PASSWORD` (A)

- **Mapping**: `DD_CRASHTRACKING_PROXY_PASSWORD` ↔ `CrashTrackingConfig.CRASH_TRACKING_PROXY_PASSWORD` (`"crashtracking.proxy.password"`).
- **Evidence**:
  - `dd-java-agent/agent-crashtracking/src/main/java/datadog/crashtracking/CrashUploader.java:167-178`: proxy password is passed to the OkHttp client builder used for crash uploads.
- **Inference**: Configures the HTTP proxy password (authentication) for Crash Tracking uploads.

### `DD_CRASHTRACKING_TAGS` (A)

- **Mapping**: `DD_CRASHTRACKING_TAGS` ↔ `CrashTrackingConfig.CRASH_TRACKING_TAGS` (`"crashtracking.tags"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2143`: reads crash tracking tags via `configProvider.getMergedMap(CRASH_TRACKING_TAGS)`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:4914-4935`: `getMergedCrashTrackingTags()` merges global tags + JMX tags + crash-tracking tags + runtime tags and adds service/env/version/language/host.
  - `dd-java-agent/agent-crashtracking/src/main/java/datadog/crashtracking/ConfigManager.java:155-159,188-196`: serializes merged tags into the crash-tracking config file.
- **Inference**: Adds/overrides tags attached to crash tracking payloads, merged with other tag sources.

### `DD_CRASHTRACKING_UPLOAD_TIMEOUT` (A)

- **Mapping**: `DD_CRASHTRACKING_UPLOAD_TIMEOUT` ↔ `CrashTrackingConfig.CRASH_TRACKING_UPLOAD_TIMEOUT` (`"crashtracking.upload.timeout"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/CrashTrackingConfig.java:15-16`: default is `2` (seconds).
  - `dd-java-agent/agent-crashtracking/src/main/java/datadog/crashtracking/CrashUploader.java:162-178`: reads the integer value and uses it as an OkHttp timeout (seconds → millis).
- **Inference**: Controls the crash uploader’s HTTP timeout.

### `DD_CWS_ENABLED` (A)

- **Mapping**: `DD_CWS_ENABLED` ↔ `CwsConfig.CWS_ENABLED` (`"cws.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:276,321-323,1316-1344`: when enabled, registers the CWS TLS scope listener (`datadog.cws.tls.TlsScopeListener`) with the tracer.
  - `dd-java-agent/cws-tls/src/main/java/datadog/cws/tls/TlsScopeListener.java:50-63`: on scope activation/close, registers the active trace/span into TLS.
- **Inference**: Enables Cloud Workload Security integration wiring so eBPF/CWS can correlate events with the active trace/span.

### `DD_CWS_TLS_REFRESH` (A)

- **Mapping**: `DD_CWS_TLS_REFRESH` ↔ `CwsConfig.CWS_TLS_REFRESH` (`"cws.tls.refresh"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:243`: default is `5000` ms.
  - `dd-java-agent/cws-tls/src/main/java/datadog/cws/tls/TlsFactory.java:11-15`: passes `Config.get().getCwsTlsRefresh()` to `ErpcTls`.
  - `dd-java-agent/cws-tls/src/main/java/datadog/cws/tls/ErpcTls.java:93-103`: background thread sleeps `refresh` and periodically re-registers the TLS pointer with eRPC.
- **Inference**: Controls how often the CWS TLS pointer is refreshed/registered via eRPC.

### `DD_DATA_JOBS_COMMAND_PATTERN` (A)

- **Mapping**: `DD_DATA_JOBS_COMMAND_PATTERN` ↔ `GeneralConfig.DATA_JOBS_COMMAND_PATTERN` (`"data.jobs.command.pattern"`).
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:457-466`: matches the JVM command line against this regex; if it does not match, the tracer does not install when Data Jobs Monitoring is enabled.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:1758-1773`: invalid regex values are warned about and treated as “supported”.
- **Inference**: Safety/compatibility gate for Data Jobs Monitoring installation, based on command-line matching.

### `DD_DATA_JOBS_ENABLED` (A)

- **Mapping**: `DD_DATA_JOBS_ENABLED` ↔ `GeneralConfig.DATA_JOBS_ENABLED` (`"data.jobs.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:434-467`: when enabled, auto-enables Spark/Spark-executor integrations, long-running traces, Data Streams Monitoring, and validates the command via `DD_DATA_JOBS_COMMAND_PATTERN`.
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:245`: default is `false`.
- **Inference**: Master toggle for Data Jobs Monitoring (Spark-focused) and related default integration enablement.

### `DD_DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED` (A)

- **Mapping**: `DD_DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED` ↔ `GeneralConfig.DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED` (`"data.jobs.experimental_features.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:249`: default is `false`.
  - `dd-java-agent/instrumentation/spark/spark_2.12/src/main/java/datadog/trace/instrumentation/spark/Spark212Instrumentation.java:105-118`: enables extracting formatted Spark plan metadata when `SparkPlanInfo` metadata is empty (same gate as parse-spark-plan).
- **Inference**: Opt-in for experimental Data Jobs/Spark metadata extraction behavior.

### `DD_DATA_JOBS_OPENLINEAGE_ENABLED` (A)

- **Mapping**: `DD_DATA_JOBS_OPENLINEAGE_ENABLED` ↔ `GeneralConfig.DATA_JOBS_OPENLINEAGE_ENABLED` (`"data.jobs.openlineage.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:246`: default is `false`.
  - `dd-java-agent/instrumentation/spark/spark_2.12/src/main/java/datadog/trace/instrumentation/spark/Spark212Instrumentation.java:66-88`: injects `io.openlineage.spark.agent.OpenLineageSparkListener` into `spark.extraListeners` when enabled and OpenLineage classes are present.
- **Inference**: Enables OpenLineage listener injection for Spark runs to support OpenLineage event emission.

### `DD_DATA_JOBS_OPENLINEAGE_TIMEOUT_ENABLED` (A)

- **Mapping**: `DD_DATA_JOBS_OPENLINEAGE_TIMEOUT_ENABLED` ↔ `GeneralConfig.DATA_JOBS_OPENLINEAGE_TIMEOUT_ENABLED` (`"data.jobs.openlineage.timeout.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:247`: default is `true`.
  - `dd-java-agent/instrumentation/spark/spark-common/src/main/java/datadog/trace/instrumentation/spark/AbstractDatadogSparkListener.java:1349-1371`: when enabled (and supported), configures OpenLineage circuit breaker settings to use a timeout circuit breaker unless another circuit breaker is already configured.
- **Inference**: Enables a timeout safety mechanism for Spark OpenLineage operations when supported.

### `DD_DATA_STREAMS_BUCKET_DURATION_SECONDS` (A)

- **Mapping**: `DD_DATA_STREAMS_BUCKET_DURATION_SECONDS` ↔ `GeneralConfig.DATA_STREAMS_BUCKET_DURATION_SECONDS` (`"data.streams.bucket_duration.seconds"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:252`: default is `10` seconds.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2750-2752,4607-4611`: reads a float “seconds” value and converts it to nanoseconds (rounded to ms).
  - `dd-trace-core/src/main/java/datadog/trace/core/datastreams/DefaultDataStreamsMonitoring.java:151-157`: schedules DSM reporting at `bucketDurationNanos` interval.
- **Inference**: Controls the DSM aggregation/reporting interval (bucket duration).

### `DD_DATA_STREAMS_ENABLED` (A)

- **Mapping**: `DD_DATA_STREAMS_ENABLED` ↔ `GeneralConfig.DATA_STREAMS_ENABLED` (`"data.streams.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:251`: default is `false`.
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:820-822`: only registers the DSM propagator when enabled.
- **Inference**: Master toggle for Data Streams Monitoring propagation/reporting.

### `DD_DBM_TRACE_PREPARED_STATEMENTS` (A)

- **Mapping**: `DD_DBM_TRACE_PREPARED_STATEMENTS` ↔ `TraceInstrumentationConfig.DB_DBM_TRACE_PREPARED_STATEMENTS` (`"dbm.trace_prepared_statements"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:75`: default is `false`.
  - `dd-java-agent/instrumentation/jdbc/src/main/java/datadog/trace/instrumentation/jdbc/AbstractPreparedStatementInstrumentation.java:91-94`: when enabled for Postgres prepared statements, calls `DECORATE.setApplicationName(span, connection)`.
  - `dd-java-agent/instrumentation/jdbc/src/main/java/datadog/trace/instrumentation/jdbc/JDBCDecorator.java:356-360,365-377`: `setApplicationName` sets a `_DD_`-prefixed W3C traceparent into `pg_stat_activity.application_name` for prepared statements (extra DB round trip).
- **Inference**: Enables special Postgres prepared-statement trace-context propagation for DBM/APM linking via `application_name`.

### `DD_DISTRIBUTED_DEBUGGER_ENABLED` (A)

- **Mapping**: `DD_DISTRIBUTED_DEBUGGER_ENABLED` ↔ `DebuggerConfig.DISTRIBUTED_DEBUGGER_ENABLED` (`"distributed.debugger.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2516-2518`: reads the flag into `distributedDebuggerEnabled` (default `false`).
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerTransformer.java:700-703`: when disabled, logs that Trigger probes will not be installed.
- **Inference**: Feature gate for “trigger probes” / distributed debugger behavior within the dynamic instrumentation transformer.

### `DD_DOGSTATSD_ARGS` (A)

- **Mapping**: `DD_DOGSTATSD_ARGS` ↔ `GeneralConfig.DOGSTATSD_ARGS` (`"dogstatsd.args"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2768-2774`: reads a string and parses it into a list of non-empty arguments.
  - `communication/src/main/java/datadog/communication/ddagent/ExternalAgentLauncher.java:40-48`: when `DD_DOGSTATSD_PATH` is set (Azure App Services), appends these args to the external DogStatsD process command.
- **Inference**: Extra CLI args used when the tracer launches an external DogStatsD process in Azure App Services mode.

### `DD_DOGSTATSD_PATH` (A)

- **Mapping**: `DD_DOGSTATSD_PATH` ↔ `GeneralConfig.DOGSTATSD_PATH` (`"dogstatsd.path"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2767,4621-4623`: reads/stores the path.
  - `communication/src/main/java/datadog/communication/ddagent/ExternalAgentLauncher.java:40-51`: when set (Azure App Services), starts a `dogstatsd` external process using this path (and args from `DD_DOGSTATSD_ARGS`) and supervises it.
- **Inference**: Configures the DogStatsD executable path for Azure App Services “external process” launching.

### `DD_DOGSTATSD_PIPE_NAME` (A)

- **Mapping**: `DD_DOGSTATSD_PIPE_NAME` ↔ `GeneralConfig.DOGSTATSD_NAMED_PIPE` (`"dogstatsd.pipe.name"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1870-1874,3413-3419`: reads and exposes the named pipe + start delay.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:65-77,93-94`: passes the named pipe to the StatsD client manager (so metrics can be sent via pipe instead of UDP).
  - `communication/src/main/java/datadog/communication/ddagent/ExternalAgentLauncher.java:66-83`: used as a Windows named-pipe health check for the externally launched DogStatsD process (`\\\\.\\pipe\\<name>`).
- **Inference**: Configures the DogStatsD named pipe for StatsD clients, and serves as a readiness/health signal for the optional externally launched DogStatsD process in Azure App Services.

### `DD_DOGSTATSD_START_DELAY` (A)

- **Mapping**: `DD_DOGSTATSD_START_DELAY` ↔ `GeneralConfig.DOGSTATSD_START_DELAY` (`"dogstatsd.start-delay"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:115`: default is `15` seconds.
  - `products/metrics/metrics-lib/src/main/java/datadog/metrics/impl/statsd/DDAgentStatsDConnection.java:80-95`: delays the initial StatsD connection attempt until `startDelay - (now - tracerStartTime)` seconds have elapsed.
- **Inference**: Startup delay before establishing the StatsD/DogStatsD client connection (helps when DogStatsD/JMXFetch aren’t ready immediately).

### `DD_DYNAMIC_INSTRUMENTATION_CAPTURE_TIMEOUT` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_CAPTURE_TIMEOUT` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_CAPTURE_TIMEOUT` (`"dynamic.instrumentation.capture.timeout"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:215`: default is `100` ms.
  - `dd-java-agent/agent-debugger/debugger-bootstrap/src/main/java/datadog/trace/bootstrap/debugger/DebuggerContext.java:326-329`: uses the timeout when freezing captured context for snapshot probes.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/util/ValueScriptHelper.java:14-18`: uses the timeout when serializing captured values.
- **Inference**: Upper bound (ms) for snapshot capture/serialization work to prevent dynamic instrumentation from hanging application threads.

### `DD_DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED` (`"dynamic.instrumentation.classfile.dump.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:208`: default is `false`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerTransformer.java:915-931`: dumps original/instrumented classfiles when enabled.
- **Inference**: Debug knob to write `.class` dumps during transformation for troubleshooting live instrumentation.

### `DD_DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL` (`"dynamic.instrumentation.diagnostics.interval"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:210`: default is `60 * 60` seconds.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/sink/ProbeStatusSink.java:61-64,223-224`: interval controls how frequently probe statuses can be emitted again.
- **Inference**: Controls how often probe status/diagnostic messages may be re-sent.

### `DD_DYNAMIC_INSTRUMENTATION_EXCLUDE_FILES` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_EXCLUDE_FILES` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_EXCLUDE_FILES` (`"dynamic.instrumentation.exclude.files"`).
- **Evidence**:
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerTransformer.java:129-147,199-227`: in Instrument-The-World mode, reads a comma-separated list of file paths and loads exclude rules from those files (prefixes ending in `*`, classes, or `Class::method` entries).
- **Inference**: Provides exclude rule files to scope Instrument-The-World mode.

### `DD_DYNAMIC_INSTRUMENTATION_INCLUDE_FILES` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_INCLUDE_FILES` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_INCLUDE_FILES` (`"dynamic.instrumentation.include.files"`).
- **Evidence**:
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerTransformer.java:129-147,199-227`: in Instrument-The-World mode, reads a comma-separated list of file paths and loads include rules from those files.
- **Inference**: Provides include rule files to scope Instrument-The-World mode.

### `DD_DYNAMIC_INSTRUMENTATION_INSTRUMENT_THE_WORLD` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_INSTRUMENT_THE_WORLD` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_INSTRUMENT_THE_WORLD` (`"dynamic.instrumentation.instrument.the.world"`).
- **Evidence**:
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerTransformer.java:129-158`: enables ITW mode when set; expects `method` or `line` to decide how probes are created (invalid values log a warning).
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/uploader/BatchUploader.java:137,203-207`: disables uploads when ITW mode is enabled.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/sink/ProbeStatusSink.java:64-66,183-187`: drops diagnostics when ITW mode is enabled.
- **Inference**: Enables the “Instrument-The-World” debug/testing mode and controls its probe granularity.

### `DD_DYNAMIC_INSTRUMENTATION_LOCALVAR_HOISTING_LEVEL` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_LOCALVAR_HOISTING_LEVEL` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_LOCALVAR_HOISTING_LEVEL` (`"dynamic.instrumentation.localvar.hoisting.level"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:216`: default is `1`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/instrumentation/CapturedContextInstrumenter.java:461-472`: if `0`, disables hoisting; otherwise runs `LocalVarHoisting.processMethod(methodNode, hoistingLevel)` for Java.
- **Inference**: Controls how aggressively dynamic instrumentation hoists local variables to capture them.

### `DD_DYNAMIC_INSTRUMENTATION_MAX_PAYLOAD_SIZE` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_MAX_PAYLOAD_SIZE` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_MAX_PAYLOAD_SIZE` (`"dynamic.instrumentation.max.payload.size"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:213`: default is `1024` KiB.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2556-2560`: reads KiB and converts to bytes (`* 1024L`).
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerAgent.java:174-185`: when `DD_DYNAMIC_INSTRUMENTATION_PROBE_FILE` is set, loads the file using this max payload size.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/ConfigurationFileLoader.java:35-39`: enforces the size limit via `SizeCheckedInputStream`.
- **Inference**: Caps the size of local probe definition file reads to prevent large/untrusted payloads from being loaded.

### `DD_DYNAMIC_INSTRUMENTATION_METRICS_ENABLED` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_METRICS_ENABLED` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_METRICS_ENABLED` (`"dynamic.instrumentation.metrics.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:211`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2546-2550`: additionally gated by `runtimeMetricsEnabled`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/util/DebuggerMetrics.java:16-29`: uses the flag to decide whether to create a real StatsD client or a no-op.
- **Inference**: Enables internal dynamic-instrumentation/debugger metrics emission via DogStatsD.

### `DD_DYNAMIC_INSTRUMENTATION_POLL_INTERVAL` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_POLL_INTERVAL` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_POLL_INTERVAL` (`"dynamic.instrumentation.poll.interval"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:209`: default is `1` second.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2539-2541,4296-4298`: reads/exposes the value via getter.
  - No runtime usage sites were found beyond reading/storing this value.
- **Inference**: Intended to control a polling interval for Dynamic Instrumentation, but appears unused in this version.

### `DD_DYNAMIC_INSTRUMENTATION_PROBE_FILE` (B)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_PROBE_FILE` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_PROBE_FILE` (`"dynamic.instrumentation.probe.file"`).
- **Evidence**:
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerAgent.java:174-186`: if set, loads probe definitions from this local file (bounded by `DD_DYNAMIC_INSTRUMENTATION_MAX_PAYLOAD_SIZE`) and applies them as a LOCAL_FILE configuration source.
- **Inference**: Allows local, file-based probe configuration instead of remote Live Debugging configuration.

### `DD_DYNAMIC_INSTRUMENTATION_REDACTED_IDENTIFIERS` (B)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_REDACTED_IDENTIFIERS` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_REDACTED_IDENTIFIERS` (`"dynamic.instrumentation.redacted.identifiers"`).
- **Evidence**:
  - `dd-java-agent/agent-debugger/debugger-bootstrap/src/main/java/datadog/trace/bootstrap/debugger/util/Redaction.java:126-135`: parses comma-separated identifiers and adds them (normalized) to the redaction keyword set.
- **Inference**: Lets users add custom “sensitive” keywords that will be redacted from captured snapshot data.

### `DD_DYNAMIC_INSTRUMENTATION_REDACTED_TYPES` (B)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_REDACTED_TYPES` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_REDACTED_TYPES` (`"dynamic.instrumentation.redacted.types"`).
- **Evidence**:
  - `dd-java-agent/agent-debugger/debugger-bootstrap/src/main/java/datadog/trace/bootstrap/debugger/util/Redaction.java:137-167`: parses comma-separated types, supports wildcard `*`, and builds a trie used by `isRedactedType`.
- **Inference**: Lets users mark types/packages as sensitive so their values are treated as redacted.

### `DD_DYNAMIC_INSTRUMENTATION_REDACTION_EXCLUDED_IDENTIFIERS` (B)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_REDACTION_EXCLUDED_IDENTIFIERS` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_REDACTION_EXCLUDED_IDENTIFIERS` (`"dynamic.instrumentation.redaction.excluded.identifiers"`).
- **Evidence**:
  - `dd-java-agent/agent-debugger/debugger-bootstrap/src/main/java/datadog/trace/bootstrap/debugger/util/Redaction.java:120-123`: removes configured excluded keywords from the predefined redaction keyword set (after normalization).
- **Inference**: Allows “un-redacting” certain keywords from the built-in sensitive keyword list.

### `DD_DYNAMIC_INSTRUMENTATION_SNAPSHOT_URL` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_SNAPSHOT_URL` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_SNAPSHOT_URL` (`"dynamic.instrumentation.snapshot.url"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:4413-4420`: overrides the debugger snapshot URL when set, otherwise uses default `/debugger/v1/diagnostics` (or agentless CI Visibility logs URL).
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/sink/DebuggerSink.java:54-60`: snapshot/log uploaders use `config.getFinalDebuggerSnapshotUrl()`.
- **Inference**: Overrides where dynamic instrumentation snapshots/logs are uploaded.

### `DD_DYNAMIC_INSTRUMENTATION_SOURCE_FILE_TRACKING_ENABLED` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_SOURCE_FILE_TRACKING_ENABLED` ↔ `DebuggerConfig.DEBUGGER_SOURCE_FILE_TRACKING_ENABLED` (`"dynamic.instrumentation.source.file.tracking.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:228`: default is `true`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerAgent.java:428-437`: installs `SourceFileTrackingTransformer` only when `Config.get().isDebuggerSourceFileTrackingEnabled()` is true.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/SourceFileTrackingTransformer.java:21-25,100-118`: transformer maps a source file name (for example `Foo.java`) to associated class names so all classes for a source file can be retransformed.
- **Inference**: Enables source-file ↔ class mapping used by Dynamic Instrumentation to retransform all classes associated with a source file.

### `DD_DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE` (`"dynamic.instrumentation.upload.batch.size"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:212`: default is `100`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/sink/SnapshotSink.java:60-62,85-91`: low-rate snapshot flush serializes and uploads up to `batchSize`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/sink/ProbeStatusSink.java:61-64,155-157`: diagnostics batching uses `batchSize` and drains up to `batchSize` statuses per flush (queue capacity is `2 * batchSize`).
- **Inference**: Controls how many snapshots/diagnostics are sent per upload request.

### `DD_DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL` (`"dynamic.instrumentation.upload.flush.interval"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:207`: default is `0` ms and means “dynamic”.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/sink/DebuggerSink.java:79-90`: if the interval is `0`, enables adaptive flush-interval logic; otherwise uses the fixed interval.
- **Inference**: Controls the low-rate debugger sink flush cadence (ms), with `0` enabling adaptive behavior.

### `DD_DYNAMIC_INSTRUMENTATION_UPLOAD_INTERVAL_SECONDS` (B)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_UPLOAD_INTERVAL_SECONDS` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_INTERVAL_SECONDS` (`"dynamic.instrumentation.upload.interval.seconds"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2522-2528`: when this key is set, the tracer computes `dynamicInstrumentationUploadFlushInterval = upload.interval.seconds * 1000`, overriding the integer `upload.flush.interval` key.
- **Inference**: Alternative “seconds” representation for the upload flush interval (takes precedence when set).

### `DD_DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT` (`"dynamic.instrumentation.upload.timeout"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:206`: default is `30` seconds.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/uploader/BatchUploader.java:157-169`: uses the value as the OkHttp request timeout for debugger uploads.
- **Inference**: Sets the HTTP timeout for debugger/dynamic-instrumentation uploads.

### `DD_DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE` (A)

- **Mapping**: `DD_DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE` ↔ `DebuggerConfig.DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE` (`"dynamic.instrumentation.verify.bytecode"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:214`: default is `true`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerTransformer.java:503-531`: when enabled, verifies instrumented bytecode using ASM’s `CheckClassAdapter` and an analyzer; throws if verification fails.
- **Inference**: Safety check to ensure generated bytecode is valid; disabling skips verification.

### `DD_EXCEPTION_DEBUGGING_ENABLED` (A)

- **Mapping**: `DD_EXCEPTION_DEBUGGING_ENABLED` ↔ `DebuggerConfig.DEBUGGER_EXCEPTION_ENABLED` (`"exception.debugging.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:221`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2596-2600`: reads `exception.debugging.enabled` into `debuggerExceptionEnabled` with `exception.replay.enabled` as a backward-compatible alias.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerAgent.java:89-91`: starts Exception Replay when `config.isDebuggerExceptionEnabled()` is true.
- **Inference**: Main enablement flag for exception debugging/Exception Replay startup.

### `DD_EXCEPTION_REPLAY_CAPTURE_INTERMEDIATE_SPANS_ENABLED` (A)

- **Mapping**: `DD_EXCEPTION_REPLAY_CAPTURE_INTERMEDIATE_SPANS_ENABLED` ↔ `DebuggerConfig.DEBUGGER_EXCEPTION_CAPTURE_INTERMEDIATE_SPANS_ENABLED` (`"exception.replay.capture.intermediate.spans.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:224`: default is `true`.
  - `dd-trace-core/src/main/java/datadog/trace/core/DDSpan.java:382-387`: when disabled (or when “root-only” is enabled), Exception Replay only handles exceptions on local root spans; intermediate spans are skipped.
- **Inference**: Controls whether Exception Replay can capture exceptions on intermediate spans or only on local root spans.

### `DD_EXCEPTION_REPLAY_CAPTURE_INTERVAL_SECONDS` (A)

- **Mapping**: `DD_EXCEPTION_REPLAY_CAPTURE_INTERVAL_SECONDS` ↔ `DebuggerConfig.DEBUGGER_EXCEPTION_CAPTURE_INTERVAL_SECONDS` (`"exception.replay.capture.interval.seconds"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:226`: default is `3600` seconds.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/exception/ExceptionProbeManager.java:61-63,140-146`: uses the interval to decide whether to capture a fingerprint again (min seconds since last capture).
- **Inference**: Rate-limits how often identical exception fingerprints are captured.

### `DD_EXCEPTION_REPLAY_CAPTURE_MAX_FRAMES` (A)

- **Mapping**: `DD_EXCEPTION_REPLAY_CAPTURE_MAX_FRAMES` ↔ `DebuggerConfig.DEBUGGER_EXCEPTION_CAPTURE_MAX_FRAMES` (`"exception.replay.capture.max.frames"`; legacy).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2616-2620`: `exception.replay.capture.max.frames` is treated as a backward-compatible alias for `exception.replay.max.frames.to.capture`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/exception/ExceptionProbeManager.java:87-99`: limits how many stack frames are instrumented for exception probes via `maxCapturedFrames`.
- **Inference**: Legacy alias to cap the number of exception stack frames instrumented/captured.

### `DD_EXCEPTION_REPLAY_ENABLED` (A)

- **Mapping**: `DD_EXCEPTION_REPLAY_ENABLED` ↔ `DebuggerConfig.EXCEPTION_REPLAY_ENABLED` (`"exception.replay.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DefaultDebuggerConfigUpdater.java:33-37`: starts/stops Exception Replay based on configuration updates.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2596-2600`: key is also used as a backward-compatible alias for `exception.debugging.enabled`.
- **Inference**: Product-level enablement for Exception Replay, also accepted as a legacy enablement alias.

### `DD_EXCEPTION_REPLAY_MAX_EXCEPTION_ANALYSIS_LIMIT` (A)

- **Mapping**: `DD_EXCEPTION_REPLAY_MAX_EXCEPTION_ANALYSIS_LIMIT` ↔ `DebuggerConfig.DEBUGGER_MAX_EXCEPTION_PER_SECOND` (`"exception.replay.max.exception.analysis.limit"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:222`: default is `100`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/exception/DefaultExceptionDebugger.java:47-56`: applies a circuit breaker limiting how many exceptions are handled per second.
- **Inference**: Throttles exception handling/analysis rate for Exception Replay.

### `DD_EXCEPTION_REPLAY_MAX_FRAMES_TO_CAPTURE` (A)

- **Mapping**: `DD_EXCEPTION_REPLAY_MAX_FRAMES_TO_CAPTURE` ↔ `DebuggerConfig.DEBUGGER_EXCEPTION_MAX_CAPTURED_FRAMES` (`"exception.replay.max.frames.to.capture"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:225`: default is `3`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/exception/ExceptionProbeManager.java:87-110`: caps how many frames are turned into exception probes (skips native / excluded / no-line frames).
- **Inference**: Caps the number of stack frames instrumented/captured per exception.

### `DD_EXPERIMENTAL_API_SECURITY_ENABLED` (A)

- **Mapping**: `DD_EXPERIMENTAL_API_SECURITY_ENABLED` ↔ `AppSecConfig.API_SECURITY_ENABLED_EXPERIMENTAL` (`"experimental.api-security.enabled"`; legacy alias).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2236-2238`: reads `api-security.enabled` with `experimental.api-security.enabled` as an alias.
  - `dd-java-agent/appsec/src/main/java/com/datadog/appsec/AppSecSystem.java:200-215`: when API Security is enabled and AppSec is active, initializes the API Security sampler and span post-processing.
- **Inference**: Legacy/experimental enablement key for API Security (alias of `api-security.enabled`).

### `DD_EXPERIMENTAL_DEFER_INTEGRATIONS_UNTIL` (A)

- **Mapping**: `DD_EXPERIMENTAL_DEFER_INTEGRATIONS_UNTIL` ↔ `TraceInstrumentationConfig.EXPERIMENTAL_DEFER_INTEGRATIONS_UNTIL` (`"experimental.defer.integrations.until"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:309`: reads the string into `deferIntegrationsUntil`.
  - `dd-java-agent/agent-builder/src/main/java/datadog/trace/agent/tooling/CombiningMatcher.java:101-116`: parses the value as a simple delay; when recognized and \u22655 seconds, schedules `resumeMatching(...)` later and defers matching.
- **Inference**: Experimental knob to defer integration matching and resume it later at a configured delay.

### `DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED` (A)

- **Mapping**: `DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED` ↔ `FeatureFlaggingConfig.FLAGGING_PROVIDER_ENABLED` (`"experimental.flagging.provider.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:1145-1156`: when enabled, loads and starts `com.datadog.featureflag.FeatureFlaggingSystem`.
  - `products/feature-flagging/feature-flagging-agent/src/main/java/com/datadog/featureflag/FeatureFlaggingSystem.java:17-29`: initializes feature-flag remote config service and exposure writer.
- **Inference**: Enables the Feature Flagging subsystem that uses Remote Config and reports feature-flag exposures/evaluations.

### `DD_EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED` (B)

- **Mapping**: `DD_EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED` ↔ `GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED` (`"experimental.propagate.process.tags.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1618`: reads the boolean (default `true`) into `experimentalPropagateProcessTagsEnabled`.
  - `internal-api/src/main/java/datadog/trace/api/ProcessTags.java:23,41-53`: when enabled, computes default process tags; otherwise produces an empty tag map/list.
  - `products/metrics/metrics-lib/src/main/java/datadog/metrics/impl/statsd/DDAgentStatsDClientManager.java:59-78`: appends process tags as constant tags for StatsD clients when present.
- **Inference**: Controls whether default process tags are computed and propagated/attached (notably to metrics as constant tags).

### `DD_FORCE_CLEAR_TEXT_HTTP_FOR_INTAKE_CLIENT` (A)

- **Mapping**: `DD_FORCE_CLEAR_TEXT_HTTP_FOR_INTAKE_CLIENT` ↔ `TracerConfig.FORCE_CLEAR_TEXT_HTTP_FOR_INTAKE_CLIENT` (`"force.clear.text.http.for.intake.client"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1534-1535,3163-3164`: reads and exposes the boolean.
  - `communication/src/main/java/datadog/communication/ddagent/SharedCommunicationObjects.java:245-248`: passes the flag to `OkHttpUtils.buildHttpClient(...)` for the intake client.
  - `communication/src/main/java/datadog/communication/http/OkHttpUtils.java:154-157`: when enabled, forces `ConnectionSpec.CLEARTEXT` to avoid TLS.
- **Inference**: Forces the tracer’s intake HTTP client to run in cleartext (no TLS), useful on JVMs/environments without TLS support.

### `DD_GIT_COMMIT_HEAD_SHA` (A)

- **Mapping**: `DD_GIT_COMMIT_HEAD_SHA` ↔ `CiVisibilityConfig.GIT_COMMIT_HEAD_SHA` (`"git.commit.head.sha"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilityRepoServices.java:147-152`: uses the configured value to build user pull-request info (`CommitInfo(config.getGitCommitHeadSha())`).
- **Inference**: User-provided git head SHA used to populate CI Visibility pull-request/git metadata when CI variables are missing or incomplete.

### `DD_GIT_PULL_REQUEST_BASE_BRANCH` (A)

- **Mapping**: `DD_GIT_PULL_REQUEST_BASE_BRANCH` ↔ `CiVisibilityConfig.GIT_PULL_REQUEST_BASE_BRANCH` (`"git.pull.request.base.branch"`).
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilityRepoServices.java:147-152`: uses the configured value as the pull-request base branch in user pull-request info.
- **Inference**: User-provided pull-request base branch used to populate CI Visibility pull-request/git metadata.

### `DD_GIT_PULL_REQUEST_BASE_BRANCH_SHA` (A)

- **Mapping**: `DD_GIT_PULL_REQUEST_BASE_BRANCH_SHA` ↔ `CiVisibilityConfig.GIT_PULL_REQUEST_BASE_BRANCH_SHA` (`"git.pull.request.base.branch.sha"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2481-2483`: reads the configured base-branch SHA.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilityRepoServices.java:147-152`: uses `config.getGitPullRequestBaseBranchSha()` to build user pull-request info.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ci/CITagsProvider.java:142-144`: sets tag `git.pull_request.base_branch_sha` from pull-request info.
- **Inference**: User-supplied PR base/target branch commit SHA used to populate CI Visibility pull-request/git metadata and tags.

### `DD_HTTP_CLIENT_TAG_HEADERS` (A)

- **Mapping**: `DD_HTTP_CLIENT_TAG_HEADERS` ↔ `TraceInstrumentationConfig.HTTP_CLIENT_TAG_HEADERS` (`"http.client.tag.headers"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1666`: reads the flag (default `true`).
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpClientDecorator.java:90-98`: when enabled, applies configured request header-to-tag mappings to HTTP client spans.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpClientDecorator.java:141-149`: when enabled, applies configured response header-to-tag mappings to HTTP client spans.
- **Inference**: Global toggle to enable/disable HTTP client header tagging (the specific headers are controlled by the header-to-tag mapping config).

### `DD_HTTP_SERVER_DECODED_RESOURCE_PRESERVE_SPACES` (A)

- **Mapping**: `DD_HTTP_SERVER_DECODED_RESOURCE_PRESERVE_SPACES` ↔ `TraceInstrumentationConfig.HTTP_SERVER_DECODED_RESOURCE_PRESERVE_SPACES` (`"http.server.decoded.resource.preserve.spaces"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1654`: reads the flag (default `true`).
  - `internal-api/src/main/java/datadog/trace/api/normalize/SimpleHttpPathNormalizer.java:16-18,50-54`: when decoding, preserves whitespace in the normalized path only if the flag is enabled.
- **Inference**: Controls whether spaces are kept vs stripped when normalizing decoded server paths into resource names.

### `DD_HTTP_SERVER_RAW_QUERY_STRING` (A)

- **Mapping**: `DD_HTTP_SERVER_RAW_QUERY_STRING` ↔ `TraceInstrumentationConfig.HTTP_SERVER_RAW_QUERY_STRING` (`"http.server.raw.query-string"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1649`: reads the flag (default `true`).
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpServerDecorator.java:269-273`: when query-string tagging is enabled, chooses `rawQuery()` vs `query()` based on this flag.
- **Inference**: Chooses whether server query-string tags use the raw (percent-encoded) vs decoded query value (when raw URL parts are available).

### `DD_HTTP_SERVER_RAW_RESOURCE` (A)

- **Mapping**: `DD_HTTP_SERVER_RAW_RESOURCE` ↔ `TraceInstrumentationConfig.HTTP_SERVER_RAW_RESOURCE` (`"http.server.raw.resource"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1651`: reads the flag (default `false`).
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpServerDecorator.java:253-257,279-281`: when enabled (and raw URL parts are supported), uses `rawPath()` for URL/resource-name building and treats the path as encoded.
- **Inference**: When supported, uses the raw request path (preserving percent-encoding) for the server URL tag and resource naming.

### `DD_HYSTRIX_MEASURED_ENABLED` (A)

- **Mapping**: `DD_HYSTRIX_MEASURED_ENABLED` ↔ `TraceInstrumentationConfig.HYSTRIX_MEASURED_ENABLED` (`"hystrix.measured.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2696`: reads the flag (default `false`).
  - `dd-java-agent/instrumentation/hystrix-1.4/src/main/java/datadog/trace/instrumentation/hystrix/HystrixDecorator.java:27-29,97-99`: `span.setMeasured(true)` when enabled.
- **Inference**: Marks Hystrix spans as “measured” so they contribute to trace metrics.

### `DD_IAST_ANONYMOUS_CLASSES_ENABLED` (A)

- **Mapping**: `DD_IAST_ANONYMOUS_CLASSES_ENABLED` ↔ `IastConfig.IAST_ANONYMOUS_CLASSES_ENABLED` (`"iast.anonymous-classes.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:165`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2295-2297`: reads the flag.
  - `dd-java-agent/instrumentation/datadog/asm/iast-instrumenter/src/main/java/datadog/trace/instrumentation/iastinstrumenter/IastInstrumentation.java:105-110`: when disabled, excludes anonymous classes matching the Java `$<digits>` naming convention.
- **Inference**: Controls whether IAST call-site instrumentation should consider anonymous classes.

### `DD_IAST_CONTEXT_MODE` (A)

- **Mapping**: `DD_IAST_CONTEXT_MODE` ↔ `IastConfig.IAST_CONTEXT_MODE` (`"iast.context.mode"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2257-2258`: reads the enum, default `REQUEST`.
  - `internal-api/src/main/java/datadog/trace/api/iast/IastContext.java:34-41`: documents `GLOBAL` vs `REQUEST` context resolution behavior.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/IastSystem.java:111-119`: uses mode to choose global vs request context provider and to build the overhead controller accordingly.
- **Inference**: Selects whether IAST state is global or request-scoped, impacting context lookup and overhead controls.

### `DD_IAST_DB_ROWS_TO_TAINT` (A)

- **Mapping**: `DD_IAST_DB_ROWS_TO_TAINT` ↔ `IastConfig.IAST_DB_ROWS_TO_TAINT` (`"iast.db.rows-to-taint"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:168`: default is `1`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2310`: reads the integer.
  - `dd-java-agent/instrumentation/jdbc/src/main/java/datadog/trace/instrumentation/jdbc/IastResultSetInstrumentation.java:99-101`: stops tainting additional ResultSet rows once the counter exceeds the configured limit.
- **Inference**: Limits how many rows per JDBC `ResultSet` are treated as taint sources to reduce overhead.

### `DD_IAST_DEBUG_ENABLED` (A)

- **Mapping**: `DD_IAST_DEBUG_ENABLED` ↔ `IastConfig.IAST_DEBUG_ENABLED` (`"iast.debug.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:145`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2255`: reads the flag.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/IastSystem.java:104-107`: sets `IastSystem.DEBUG` and logs debug/verbosity configuration.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/overhead/OverheadController.java:55-59`: wraps overhead controller in a debug adapter when debug is enabled.
- **Inference**: Enables IAST debug behavior (more detailed logging and debug overhead-controller behavior).

### `DD_IAST_DEDUPLICATION_ENABLED` (A)

- **Mapping**: `DD_IAST_DEDUPLICATION_ENABLED` ↔ `IastConfig.IAST_DEDUPLICATION_ENABLED` (`"iast.deduplication.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:164`: default is `true` (in `DEFAULT` detection mode).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2266`: value is derived from `IastDetectionMode` (forced `false` in `FULL` detection mode).
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/Reporter.java:47-52`: enables `HashBasedDeduplication` when flag is true; otherwise never dedupes.
- **Inference**: Controls whether IAST vulnerability reports are deduplicated (primarily to reduce repeated reporting noise/overhead).

### `DD_IAST_DETECTION_MODE` (A)

- **Mapping**: `DD_IAST_DETECTION_MODE` ↔ `IastConfig.IAST_DETECTION_MODE` (`"iast.detection.mode"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2259-2266,2286`: selects `IastDetectionMode` and derives sampling/concurrency/deduplication/range limits from it.
  - `internal-api/src/main/java/datadog/trace/api/iast/IastDetectionMode.java:17-70`: `FULL` disables overhead controls (100% sampling, unlimited concurrency/ranges, no dedup), while `DEFAULT` uses the configured limits.
  - `dd-java-agent/instrumentation/java/java-lang/java-lang-1.8/src/main/java/datadog/trace/instrumentation/java/lang/StringFullDetectionCallSite.java:16-19`: “full detection” call sites are enabled only when `IastEnabledChecks.isFullDetection()` is true (i.e., `FULL` mode).
- **Inference**: High-level knob to switch IAST between overhead-controlled defaults vs a “full” mode that enables extra instrumentation and removes most limits.

### `DD_IAST_ENABLED` (A)

- **Mapping**: `DD_IAST_ENABLED` ↔ `IastConfig.IAST_ENABLED` (`"iast.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:144`: default is `"false"`.
  - `internal-api/src/main/java/datadog/trace/api/ProductActivation.java:20-28`: parses values: `true`/`1` → `FULLY_ENABLED`, `inactive` → `ENABLED_INACTIVE`, otherwise `FULLY_DISABLED`.
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:261-265`: uses `ProductActivation.fromString(...)` to derive IAST activation and tracks explicit “fully disabled”.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/IastSystem.java:97-103`: IAST system only starts if IAST or AppSec activation is `FULLY_ENABLED`.
- **Inference**: Enables/disables IAST or puts it in an “inactive but instrumented” state that can be toggled via remote config.

### `DD_IAST_EXPERIMENTAL_PROPAGATION_ENABLED` (A)

- **Mapping**: `DD_IAST_EXPERIMENTAL_PROPAGATION_ENABLED` ↔ `IastConfig.IAST_EXPERIMENTAL_PROPAGATION_ENABLED` (`"iast.experimental.propagation.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2305-2306`: reads the flag (default `false`).
  - `internal-api/src/main/java/datadog/trace/api/iast/IastEnabledChecks.java:30-32`: exposes it via `isExperimentalPropagationEnabled()`.
  - `dd-java-agent/instrumentation/java/java-lang/java-lang-1.8/src/main/java/datadog/trace/instrumentation/java/lang/StringExperimentalCallSite.java:18-22,25-55`: experimental propagation call sites are only enabled when the check passes (adds special handling for `String.replace*` operations).
- **Inference**: Opt-in switch for additional/experimental taint propagation behaviors.

### `DD_IAST_HARDCODED_SECRET_ENABLED` (A)

- **Mapping**: `DD_IAST_HARDCODED_SECRET_ENABLED` ↔ `IastConfig.IAST_HARDCODED_SECRET_ENABLED` (`"iast.hardcoded-secret.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:161`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2292-2294`: reads the flag.
  - `dd-java-agent/instrumentation/datadog/asm/iast-instrumenter/src/main/java/datadog/trace/instrumentation/iastinstrumenter/IastInstrumentation.java:55-66`: when enabled and IAST is active, registers the hardcoded secret listener.
- **Inference**: Enables/disables hardcoded secret detection within IAST.

### `DD_IAST_MAX_CONCURRENT_REQUESTS` (B)

- **Mapping**: `DD_IAST_MAX_CONCURRENT_REQUESTS` ↔ `IastConfig.IAST_MAX_CONCURRENT_REQUESTS` (`"iast.max-concurrent-requests"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:146`: default is `4` (in `DEFAULT` detection mode).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2262`: derived from `IastDetectionMode` (unlimited in `FULL` mode).
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/overhead/OverheadController.java:42-47`: built with `config.getIastMaxConcurrentRequests()` to constrain concurrent analysis.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/IastSystem.java:114-119`: passes max concurrent requests when building the overhead controller.
- **Inference**: Overhead-control knob limiting how many requests can be concurrently analyzed by IAST (ignored in `FULL` detection mode).

### `DD_IAST_MAX_RANGE_COUNT` (A)

- **Mapping**: `DD_IAST_MAX_RANGE_COUNT` ↔ `IastConfig.IAST_MAX_RANGE_COUNT` (`"iast.max-range-count"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:158`: default is `10` (in `DEFAULT` detection mode).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2286`: derived from `IastDetectionMode` (unlimited in `FULL` mode).
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/taint/TaintedObject.java:18,31-37`: hard cap on stored taint ranges; extra ranges are truncated to `MAX_RANGE_COUNT`.
- **Inference**: Controls memory/overhead by capping how many taint ranges each tainted value can carry.

### `DD_IAST_REDACTION_ENABLED` (A)

- **Mapping**: `DD_IAST_REDACTION_ENABLED` ↔ `IastConfig.IAST_REDACTION_ENABLED` (`"iast.redaction.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:153`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2276-2278`: reads the flag.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/model/json/EvidenceAdapter.java:59-63`: chooses redacted vs default JSON encoding based on this flag.
- **Inference**: Master switch to enable/disable evidence/source redaction in IAST reports.

### `DD_IAST_REDACTION_NAME_PATTERN` (B)

- **Mapping**: `DD_IAST_REDACTION_NAME_PATTERN` ↔ `IastConfig.IAST_REDACTION_NAME_PATTERN` (`"iast.redaction.name.pattern"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:154-155`: default regex matches common secret-ish names (password/token/key/etc).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2278-2279`: reads the configured pattern.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/sensitive/SensitiveHandlerImpl.java:38-41,55-57`: compiles and uses the pattern (case-insensitive) to decide if a name is sensitive.
- **Inference**: Tunes which “names” (parameter/header/cookie/etc) should trigger redaction.

### `DD_IAST_REDACTION_VALUE_PATTERN` (B)

- **Mapping**: `DD_IAST_REDACTION_VALUE_PATTERN` ↔ `IastConfig.IAST_REDACTION_VALUE_PATTERN` (`"iast.redaction.value.pattern"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:156-157`: default regex matches sensitive token/key formats (bearer tokens, JWTs, private keys, etc).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2280-2282`: reads the configured pattern.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/sensitive/SensitiveHandlerImpl.java:40-42,60-62`: compiles and uses the pattern (case-insensitive, multiline) to decide if a value is sensitive.
- **Inference**: Tunes which “values” should be considered sensitive and redacted.

### `DD_IAST_REQUEST_SAMPLING` (B)

- **Mapping**: `DD_IAST_REQUEST_SAMPLING` ↔ `IastConfig.IAST_REQUEST_SAMPLING` (`"iast.request-sampling"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:148`: default is `33` (in `DEFAULT` detection mode).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2265,3848-3849`: derived from `IastDetectionMode` and exposed via `getIastRequestSampling()`.
  - `internal-api/src/main/java/datadog/trace/api/iast/IastDetectionMode.java:58-60`: `DEFAULT` mode reads the configured sampling value; `FULL` mode forces `100%`.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/overhead/OverheadController.java:169-197,332-341`: sampling is used to decide whether to acquire/analyze a request; values `<= 0` are treated as `100%` (not a disable switch).
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/IastSystem.java:114-119`: used when building the IAST overhead controller (request-scoped contexts).
- **Inference**: Controls how many requests are analyzed by IAST (percentage), as part of overhead control.

### `DD_IAST_SECURITY_CONTROLS_CONFIGURATION` (B)

- **Mapping**: `DD_IAST_SECURITY_CONTROLS_CONFIGURATION` ↔ `IastConfig.IAST_SECURITY_CONTROLS_CONFIGURATION` (`"iast.security-controls.configuration"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2308,3912`: reads/exposes the configuration string.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/IastSystem.java:136-146`: when set, parses it and installs an `IastSecurityControlTransformer`.
  - `internal-api/src/main/java/datadog/trace/api/iast/securitycontrol/SecurityControlFormatter.java:29-55,63-100`: parses a semicolon-separated string into `SecurityControl` entries (types include `SANITIZER` / `INPUT_VALIDATOR`).
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/securitycontrol/IastSecurityControlTransformer.java:36-45`: only transforms classes that match configured security controls.
- **Inference**: Enables user-defined security controls so IAST can treat specific code paths as sanitizing/validating data for certain vulnerability types.

### `DD_IAST_SOURCE_MAPPING_ENABLED` (A)

- **Mapping**: `DD_IAST_SOURCE_MAPPING_ENABLED` ↔ `IastConfig.IAST_SOURCE_MAPPING_ENABLED` (`"iast.source-mapping.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2298,3888-3889`: reads/exposes the flag (default `false`).
  - `dd-java-agent/instrumentation/datadog/asm/iast-instrumenter/src/main/java/datadog/trace/instrumentation/iastinstrumenter/SourceMapperImpl.java:11-16`: provides a `SourceMapper` instance only when enabled.
- **Inference**: Enables mapping from bytecode lines to original source file/line via SMAP/stratum data.

### `DD_IAST_SOURCE_MAPPING_MAX_SIZE` (A)

- **Mapping**: `DD_IAST_SOURCE_MAPPING_MAX_SIZE` ↔ `IastConfig.IAST_SOURCE_MAPPING_MAX_SIZE` (`"iast.source-mapping.max-size"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2299,3892-3893`: reads/exposes the limit (default `1000`).
  - `dd-java-agent/instrumentation/datadog/asm/iast-instrumenter/src/main/java/datadog/trace/instrumentation/iastinstrumenter/IastInstrumentation.java:61-66`: initializes `StratumManager` using this limit.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/stratum/StratumManager.java:26-47,117-147`: stores SMAP/stratum mappings per class until the limit is reached, then stops collecting more.
- **Inference**: Caps source-mapping memory/CPU by limiting how many classes can have stored SMAP mappings.

### `DD_IAST_STACKTRACE_ENABLED` (A)

- **Mapping**: `DD_IAST_STACKTRACE_ENABLED` ↔ deprecated alias of `DD_IAST_STACK_TRACE_ENABLED` (internal key `iast.stacktrace.enabled` as a fallback for `iast.stack-trace.enabled`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/IastConfig.java:31-33`: deprecated internal key `iast.stacktrace.enabled`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2300-2304`: reads `iast.stack-trace.enabled` with `iast.stacktrace.enabled` as a fallback alias.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/Reporter.java:80-88`: when enabled, attaches a user-code stack trace to vulnerability reports (via `stackId`).
- **Inference**: Backward-compatible alias to control stack trace capture for IAST vulnerabilities.

### `DD_IAST_STACKTRACE_LEAK_SUPPRESS` (A)

- **Mapping**: `DD_IAST_STACKTRACE_LEAK_SUPPRESS` ↔ deprecated alias of `DD_IAST_STACK_TRACE_LEAK_SUPPRESS` (internal key `iast.stacktrace-leak.suppress` as a fallback for `iast.stack-trace-leak.suppress`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/IastConfig.java:19-21`: deprecated internal key `iast.stacktrace-leak.suppress`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2287-2291`: reads `iast.stack-trace-leak.suppress` with deprecated alias fallback.
  - `dd-java-agent/instrumentation/tomcat/tomcat-appsec/tomcat-appsec-7.0/src/main/java/datadog/trace/instrumentation/tomcat7/ErrorReportValueAdvice.java:45-70`: when enabled (and IAST fully enabled), replaces Tomcat error report output to avoid leaking the stack trace.
- **Inference**: Backward-compatible alias to suppress stack trace leaks in Tomcat error pages.

### `DD_IAST_STACK_TRACE_ENABLED` (B)

- **Mapping**: `DD_IAST_STACK_TRACE_ENABLED` ↔ `IastConfig.IAST_STACK_TRACE_ENABLED` (`"iast.stack-trace.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:167`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2300-2304`: reads the boolean (with `DD_IAST_STACKTRACE_ENABLED` as alias).
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/Reporter.java:80-88,92-103`: when enabled, captures and stores a user-code stack trace event and attaches its id to the vulnerability.
- **Inference**: Enables stack trace capture for IAST vulnerabilities to aid debugging/triage.

### `DD_IAST_STACK_TRACE_LEAK_SUPPRESS` (A)

- **Mapping**: `DD_IAST_STACK_TRACE_LEAK_SUPPRESS` ↔ `IastConfig.IAST_STACK_TRACE_LEAK_SUPPRESS` (`"iast.stack-trace-leak.suppress"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:159`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2287-2291,3876-3877`: reads/exposes the boolean (with deprecated alias fallback).
  - `dd-java-agent/instrumentation/tomcat/tomcat-appsec/tomcat-appsec-7.0/src/main/java/datadog/trace/instrumentation/tomcat7/ErrorReportValueAdvice.java:45-70`: when enabled, suppresses Tomcat stack trace error report rendering by writing a safe template instead.
- **Inference**: Prevents stack traces from being exposed in Tomcat error responses (stacktrace leak suppression).

### `DD_IAST_TELEMETRY_VERBOSITY` (B)

- **Mapping**: `DD_IAST_TELEMETRY_VERBOSITY` ↔ `IastConfig.IAST_TELEMETRY_VERBOSITY` (`"iast.telemetry.verbosity"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2274-2275,3852-3854`: reads the enum; returns `OFF` when tracer telemetry is disabled.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/IastSystem.java:105-109,125-128`: controls whether IAST telemetry collectors/handlers are installed.
  - `dd-java-agent/instrumentation/datadog/asm/iast-instrumenter/src/main/java/datadog/trace/instrumentation/iastinstrumenter/IastInstrumentation.java:129-133`: enables telemetry call sites when verbosity is not `OFF`.
- **Inference**: Controls the amount of IAST telemetry emitted and whether telemetry-enabled call sites are active.

### `DD_IAST_TRUNCATION_MAX_VALUE_LENGTH` (A)

- **Mapping**: `DD_IAST_TRUNCATION_MAX_VALUE_LENGTH` ↔ `IastConfig.IAST_TRUNCATION_MAX_VALUE_LENGTH` (`"iast.truncation.max.value.length"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:163`: default is `250`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2283-2286,3868-3869`: reads/exposes the integer.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/model/json/TruncationUtils.java:10-24`: truncates serialized values above the threshold and marks them as truncated.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/propagation/PropagationModuleImpl.java:27-29`: uses the threshold to prevent copying very large values during propagation.
- **Inference**: Limits evidence/source payload size and propagation overhead by truncating large values.

### `DD_IAST_VULNERABILITIES_PER_REQUEST` (A)

- **Mapping**: `DD_IAST_VULNERABILITIES_PER_REQUEST` ↔ `IastConfig.IAST_VULNERABILITIES_PER_REQUEST` (`"iast.vulnerabilities-per-request"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:147`: default is `2` (in `DEFAULT` detection mode).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2263-2264`: derived from `IastDetectionMode` (unlimited in `FULL` mode).
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/overhead/OverheadContext.java:52-60`: creates a per-request quota semaphore based on the configured limit.
- **Inference**: Controls how many vulnerabilities can be reported per request to cap overhead.

### `DD_IAST_WEAK_CIPHER_ALGORITHMS` (A)

- **Mapping**: `DD_IAST_WEAK_CIPHER_ALGORITHMS` ↔ `IastConfig.IAST_WEAK_CIPHER_ALGORITHMS` (`"iast.weak-cipher.algorithms"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:151-152`: default is a regex matching many weak cipher algorithm names.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2271-2273,3187`: reads/compiles the regex into a `Pattern`.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/sink/WeakCipherModuleImpl.java:21-27`: reports `WEAK_CIPHER` only when the algorithm id matches the pattern.
- **Inference**: Defines which cipher algorithms should be treated as weak for IAST weak-cipher detection.

### `DD_IAST_WEAK_HASH_ALGORITHMS` (B)

- **Mapping**: `DD_IAST_WEAK_HASH_ALGORITHMS` ↔ `IastConfig.IAST_WEAK_HASH_ALGORITHMS` (`"iast.weak-hash.algorithms"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:149-150`: default set includes weak hashes like `SHA1`, `MD5`, etc.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2267-2269,3183`: reads the configured set into `Config`.
  - `dd-java-agent/agent-iast/src/main/java/com/datadog/iast/sink/WeakHashModuleImpl.java:21-27`: reports `WEAK_HASH` only when the uppercased algorithm id is in the set.
- **Inference**: Defines which hashing algorithms should be treated as weak for IAST weak-hash detection.

### `DD_ID_GENERATION_STRATEGY` (A)

- **Mapping**: `DD_ID_GENERATION_STRATEGY` ↔ `TracerConfig.ID_GENERATION_STRATEGY` (`"id.generation.strategy"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1425-1455`: reads the strategy name, defaults to `RANDOM`, falls back on unknown values, and warns on unsupported non-random strategies.
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:1922-1924`: uses the configured strategy to generate new trace ids when starting a new trace.
- **Inference**: Chooses how the tracer generates trace/span ids (random vs sequential, etc.), with warnings for unsafe/unsupported strategies.

### `DD_IGNITE_CACHE_INCLUDE_KEYS` (A)

- **Mapping**: `DD_IGNITE_CACHE_INCLUDE_KEYS` ↔ `TraceInstrumentationConfig.IGNITE_CACHE_INCLUDE_KEYS` (`"ignite.cache.include_keys"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2702`: reads the flag (default `false`).
  - `dd-java-agent/instrumentation/ignite-2.0/src/main/java/datadog/trace/instrumentation/ignite/v2/cache/IgniteCacheDecorator.java:139-141`: when enabled, adds span tag `ignite.cache.key` with `key.toString()`.
- **Inference**: Optionally includes Ignite cache keys on spans (useful for debugging but may increase cardinality).

### `DD_INJECTION_ENABLED` (C)

- **Mapping**: `DD_INJECTION_ENABLED` ↔ `GeneralConfig.SSI_INJECTION_ENABLED` (`"injection.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2945-2948`: read and stored for telemetry on Single Step Instrumentation (SSI).
  - `dd-java-agent/src/main/java/datadog/trace/bootstrap/AgentBootstrap.java:139-143,172-176`: when the env var is present, records instrumentation source as `ssi` (value is treated as a presence marker here).
  - `internal-api/src/main/java/datadog/trace/api/profiling/ProfilingEnablement.java:44-46`: profiling treats this as injected when the string contains `profiler`.
- **Inference**: SSI marker/metadata used to identify injected installs and drive related behavior (source tagging and “injected” profiling enablement).

### `DD_INJECT_FORCE` (A)

- **Mapping**: `DD_INJECT_FORCE` ↔ `GeneralConfig.SSI_INJECTION_FORCE` (`"inject.force"` / system property `dd.inject.force`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:271`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2946-2948`: reads the flag for SSI telemetry.
  - `dd-java-agent/src/main/java/datadog/trace/bootstrap/AgentBootstrap.java:260-268,271-300`: bypasses SSI abort when multiple Java agents are detected by forcing injection.
- **Inference**: Overrides SSI guardrails to allow the tracer javaagent to start even when multiple JVM agents are present.

### `DD_INSTRUMENTATION_CONFIG_ID` (B)

- **Mapping**: `DD_INSTRUMENTATION_CONFIG_ID` ↔ `TraceInstrumentationConfig.INSTRUMENTATION_CONFIG_ID` (`"instrumentation_config_id"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:336,585-587`: reads/exposes the string.
  - Telemetry tests (`telemetry/src/test/groovy/datadog/telemetry/TelemetryServiceSpecification.groovy:436-454`) assert that the key is propagated in the `app-started` configuration payload when provided.
- **Inference**: Correlation/metadata string propagated via telemetry to identify the configuration/injection used for this tracer setup.

### `DD_INSTRUMENTATION_INSTALL_ID` (A)

- **Mapping**: direct environment variable read by telemetry (`DD_INSTRUMENTATION_INSTALL_ID`).
- **Evidence**:
  - `telemetry/src/main/java/datadog/telemetry/TelemetryRequest.java:114-120`: reads `DD_INSTRUMENTATION_INSTALL_ID` and writes install signature.
  - `telemetry/src/main/java/datadog/telemetry/TelemetryRequestBody.java:361-371`: encodes it as `install_signature.install_id` in `app-started` payload.
- **Inference**: Optional install-signature identifier for telemetry correlation (typically set by injection tooling).

### `DD_INSTRUMENTATION_INSTALL_TIME` (A)

- **Mapping**: direct environment variable read by telemetry (`DD_INSTRUMENTATION_INSTALL_TIME`).
- **Evidence**:
  - `telemetry/src/main/java/datadog/telemetry/TelemetryRequest.java:114-120`: reads `DD_INSTRUMENTATION_INSTALL_TIME` and writes install signature.
  - `telemetry/src/main/java/datadog/telemetry/TelemetryRequestBody.java:361-371`: encodes it as `install_signature.install_time` in `app-started` payload.
- **Inference**: Optional install time metadata for telemetry correlation (often an epoch timestamp string).

### `DD_INSTRUMENTATION_INSTALL_TYPE` (A)

- **Mapping**: direct environment variable read by telemetry (`DD_INSTRUMENTATION_INSTALL_TYPE`).
- **Evidence**:
  - `telemetry/src/main/java/datadog/telemetry/TelemetryRequest.java:114-120`: reads `DD_INSTRUMENTATION_INSTALL_TYPE` and writes install signature.
  - `telemetry/src/main/java/datadog/telemetry/TelemetryRequestBody.java:361-371`: encodes it as `install_signature.install_type` in `app-started` payload.
  - `dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerSettingsSupport.java:187-191`: reads `instrumentation.install.type` (comment notes it is usually set via `DD_INSTRUMENTATION_INSTALL_TYPE`).
- **Inference**: Optional “install signature type” metadata (commonly set by injection tooling) used for telemetry correlation/attribution.

### `DD_INSTRUMENTATION_SOURCE` (A)

- **Mapping**: `DD_INSTRUMENTATION_SOURCE` ↔ `GeneralConfig.INSTRUMENTATION_SOURCE` (`"instrumentation.source"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:272`: default is `manual`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2949-2950`: reads/stores the config value.
  - `dd-java-agent/src/main/java/datadog/trace/bootstrap/AgentBootstrap.java:139-143`: bootstrap records instrumentation source as `ssi` (injected) vs `cmd_line` (non-injected).
- **Inference**: Telemetry/metadata tag describing the installation source for this tracer setup.

### `DD_INTEGRATIONS_ENABLED` (A)

- **Mapping**: `DD_INTEGRATIONS_ENABLED` ↔ `TraceInstrumentationConfig.INTEGRATIONS_ENABLED` (`"integrations.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:58`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:237,376`: reads/exposes `integrations.enabled`.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java:193-195`: module default enablement is gated by `InstrumenterConfig.get().isIntegrationsEnabled()`.
- **Inference**: Master switch controlling whether automatic instrumentation integrations are enabled by default.

### `DD_INTEGRATION_JUNIT_ENABLED` (A)

- **Mapping**: legacy sysprop/env lookup `dd.integration.junit.enabled` ↔ `DD_INTEGRATION_JUNIT_ENABLED`.
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:485-489`: for retro-compatibility, if `dd.integration.junit.enabled` is exactly `true`, CI Visibility is enabled.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:1713-1730`: `ddGetProperty` looks for the `dd.` sysprop then the `DD_...` env-var equivalent.
- **Inference**: Legacy toggle used to enable CI Visibility; superseded by dedicated CI Visibility config.

### `DD_INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME` (A)

- **Mapping**: `DD_INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME` ↔ `TraceInstrumentationConfig.INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME` (`"integration.synapse.legacy-operation-name"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1400,3123`: reads/exposes the boolean.
  - `dd-java-agent/instrumentation/synapse-3.0/src/main/java/datadog/trace/instrumentation/synapse3/SynapseServerDecorator.java:51-56`: when enabled, uses legacy span name `http.request`.
- **Inference**: Compatibility knob to preserve older span operation naming for Synapse server spans.

### `DD_INTEGRATION_TESTNG_ENABLED` (A)

- **Mapping**: legacy sysprop/env lookup `dd.integration.testng.enabled` ↔ `DD_INTEGRATION_TESTNG_ENABLED`.
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:485-489`: for retro-compatibility, if `dd.integration.testng.enabled` is exactly `true`, CI Visibility is enabled.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:1713-1730`: `ddGetProperty` looks for the `dd.` sysprop then the `DD_...` env-var equivalent.
- **Inference**: Legacy toggle used to enable CI Visibility; superseded by dedicated CI Visibility config.

### `DD_INTERNAL_EXCEPTION_REPLAY_ONLY_LOCAL_ROOT` (A)

- **Mapping**: `DD_INTERNAL_EXCEPTION_REPLAY_ONLY_LOCAL_ROOT` ↔ `DebuggerConfig.DEBUGGER_EXCEPTION_ONLY_LOCAL_ROOT` (`"internal.exception.replay.only.local.root"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2609-2611,4360-4361`: reads/exposes `debuggerExceptionOnlyLocalRoot`.
  - `dd-trace-core/src/main/java/datadog/trace/core/DDSpan.java:378-388`: when enabled, exception replay captures only on local-root spans (intermediate spans are ignored).
- **Inference**: Limits Exception Replay to local-root spans to reduce overhead and focus capture.

### `DD_INTERNAL_FORCE_SYMBOL_DATABASE_UPLOAD` (A)

- **Mapping**: `DD_INTERNAL_FORCE_SYMBOL_DATABASE_UPLOAD` ↔ `DebuggerConfig.SYMBOL_DATABASE_FORCE_UPLOAD` (`"internal.force.symbol.database.upload"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2588-2590,4340-4341`: reads/exposes the boolean.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerAgent.java:237-241`: if enabled, `startSymbolExtraction()` is invoked immediately.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/symbol/SymDBEnablement.java:54-64`: otherwise, symbol extraction is normally controlled by remote config (`symDb` record).
- **Inference**: Forces symbol extraction/upload at startup, bypassing the usual remote-config trigger.

### `DD_JMS_PROPAGATION_DISABLED_QUEUES` (A)

- **Mapping**: `DD_JMS_PROPAGATION_DISABLED_QUEUES` ↔ `TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES` (`"jms.propagation.disabled.queues"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2651-2653,4475-4478`: reads the list into a set and checks it to disable propagation for a destination.
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/SessionInstrumentation.java:129-134`: computes per-destination propagationDisabled flag.
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/JMSMessageProducerInstrumentation.java:174-177`: injects context only if propagation is enabled and the destination is not disabled.
- **Inference**: Allows disabling JMS trace-context propagation for specific queue names.

### `DD_JMS_PROPAGATION_DISABLED_TOPICS` (A)

- **Mapping**: `DD_JMS_PROPAGATION_DISABLED_TOPICS` ↔ `TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS` (`"jms.propagation.disabled.topics"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2650-2652,4475-4478`: reads the list into a set and checks it to disable propagation for a destination.
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/SessionInstrumentation.java:173-179`: computes per-destination propagationDisabled flag.
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/JMSMessageConsumerInstrumentation.java:129-131`: extracts context only if propagation is not disabled.
- **Inference**: Allows disabling JMS trace-context propagation for specific topic names.

### `DD_JMS_UNACKNOWLEDGED_MAX_AGE` (A)

- **Mapping**: `DD_JMS_UNACKNOWLEDGED_MAX_AGE` ↔ `TraceInstrumentationConfig.JMS_UNACKNOWLEDGED_MAX_AGE` (`"jms.unacknowledged.max.age"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2654,4481-4483`: reads/exposes the integer (default `3600`).
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/jms/SessionState.java:47-50,134-136,153-160`: in client-acknowledge sessions, finishes captured spans when oldest capture exceeds this age.
- **Inference**: Safety valve to avoid unbounded accumulation of unacknowledged message spans in client-acknowledge sessions.

### `DD_JMXFETCH_INITIAL_REFRESH_BEANS_PERIOD` (A)

- **Mapping**: `DD_JMXFETCH_INITIAL_REFRESH_BEANS_PERIOD` ↔ `JmxFetchConfig.JMX_FETCH_INITIAL_REFRESH_BEANS_PERIOD` (`"jmxfetch.initial-refresh-beans-period"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1947-1949,3461-3463`: reads/exposes the value.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:61-63,115-116`: passes it to jmxfetch `AppConfig` as `initialRefreshBeansPeriod`.
- **Inference**: Controls the initial cadence of MBean list refreshes for JMXFetch.

### `DD_JMXFETCH_METRICS_CONFIGS` (A)

- **Mapping**: `DD_JMXFETCH_METRICS_CONFIGS` ↔ deprecated `JmxFetchConfig.JMX_FETCH_METRICS_CONFIGS` (`"jmxfetch.metrics-configs"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/JmxFetchConfig.java:15`: constant is marked `@Deprecated`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1944-1945,3449-3451`: reads/exposes list of metric config files.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:56-60,113-115`: supplies these as `metricConfigFiles(...)` to jmxfetch `AppConfig`.
- **Inference**: Legacy way to provide extra metric config YAML files to JMXFetch.

### `DD_JMXFETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED` (A)

- **Mapping**: `DD_JMXFETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED` ↔ `JmxFetchConfig.JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED` (`"jmxfetch.multiple-runtime-services.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:102`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1959-1962,3473-3475`: reads/exposes the boolean.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:121-127`: when enabled, registers `ServiceNameCollectingTraceInterceptor` and passes it as jmxfetch `serviceNameProvider`.
- **Inference**: Enables runtime metrics/JMXFetch to use multiple service names discovered from entry traces.

### `DD_JMXFETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT` (A)

- **Mapping**: `DD_JMXFETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT` ↔ `JmxFetchConfig.JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT` (`"jmxfetch.multiple-runtime-services.limit"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:103`: default is `10`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1963-1966,3477-3478`: reads/exposes the limit.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/ServiceNameCollectingTraceInterceptor.java:27-52`: caps how many distinct service names are remembered.
- **Inference**: Bound on the number of service names considered when multiple-runtime-services is enabled (controls cardinality).

### `DD_JMXFETCH_START_DELAY` (A)

- **Mapping**: `DD_JMXFETCH_START_DELAY` ↔ `dd.jmxfetch.start-delay` (read as a `dd.` sysprop or `DD_...` env var).
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:363-373`: start delay is used to schedule JMX subsystem initialization (JMXFetch and profiling use JMX).
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:1634-1647`: parses `dd.jmxfetch.start-delay` as an integer and defaults to `15` seconds.
- **Inference**: Startup delay knob (seconds) to postpone JMX initialization/JMXFetch startup.

### `DD_KAFKA_CLIENT_BASE64_DECODING_ENABLED` (A)

- **Mapping**: `DD_KAFKA_CLIENT_BASE64_DECODING_ENABLED` ↔ `TraceInstrumentationConfig.KAFKA_CLIENT_BASE64_DECODING_ENABLED` (`"kafka.client.base64.decoding.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2648,4485-4487`: reads/exposes the boolean (default `false`).
  - `dd-java-agent/instrumentation/kafka/kafka-clients-0.11/src/main/java/datadog/trace/instrumentation/kafka_clients/TextMapExtractAdapter.java:19-38`: when enabled, base64 decodes Kafka header values before passing them to propagation extraction.
- **Inference**: Compatibility knob for environments where Kafka header values are base64-encoded/mangled.

### `DD_KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS` (A)

- **Mapping**: `DD_KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS` ↔ `TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS` (`"kafka.client.propagation.disabled.topics"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2645-2646,4467-4469`: reads list into a set and checks it per-topic.
  - `dd-java-agent/instrumentation/kafka/kafka-clients-3.8/src/main/java17/datadog/trace/instrumentation/kafka_clients38/ProducerAdvice.java:86-88`: injects headers only when the topic is not disabled.
  - `dd-java-agent/instrumentation/kafka/kafka-streams-0.11/src/main/java/datadog/trace/instrumentation/kafka_streams/KafkaStreamTaskInstrumentation.java:234-272`: when disabled, skips context extraction and starts a new span.
- **Inference**: Allows disabling Kafka trace-context propagation for specific topic names.

### `DD_MEASURE_METHODS` (A)

- **Mapping**: `DD_MEASURE_METHODS` ↔ `TraceInstrumentationConfig.MEASURE_METHODS` (`"measure.methods"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:235`: default is empty.
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:341-346`: parses the configured patterns into a method matcher.
  - `dd-java-agent/instrumentation/datadog/tracing/trace-annotation/src/main/java/datadog/trace/instrumentation/trace_annotation/TraceDecorator.java:93-95`: marks spans `measured=true` when the method matches.
  - `dd-java-agent/instrumentation/opentelemetry/opentelemetry-annotations-1.20/src/main/java/datadog/trace/instrumentation/opentelemetry/annotations/WithSpanDecorator.java:92-94`: same measured behavior for OTel `@WithSpan` spans.
- **Inference**: Lets users opt-in to “measured” spans for selected methods (affects stats/metrics).

### `DD_MESSAGE_BROKER_SPLIT_BY_DESTINATION` (A)

- **Mapping**: `DD_MESSAGE_BROKER_SPLIT_BY_DESTINATION` ↔ `TraceInstrumentationConfig.MESSAGE_BROKER_SPLIT_BY_DESTINATION` (`"message.broker.split-by-destination"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2665,4503-4505`: reads/exposes the boolean (default `false`).
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitDecorator.java:161-163`: sets span service name to queue name when enabled.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/jms/MessageConsumerState.java:28-38`: uses destination as broker service name when enabled.
  - `dd-java-agent/instrumentation/kafka/kafka-clients-0.11/src/main/java/datadog/trace/instrumentation/kafka_clients/KafkaDecorator.java:147-149`: uses topic as service name when enabled.
- **Inference**: Splits messaging spans by destination by using the destination name as the service name (higher cardinality, more detailed breakdown).

### `DD_OBFUSCATION_QUERY_STRING_REGEXP` (A)

- **Mapping**: `DD_OBFUSCATION_QUERY_STRING_REGEXP` is a legacy alias for `TraceInstrumentationConfig.OBFUSCATION_QUERY_STRING_REGEXP` (`"trace.obfuscation.query.string.regexp"`) via the fallback key `"obfuscation.query.string.regexp"`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2704-2706,4527-4529`: reads/exposes `obfuscationQueryRegexp` (null by default) with fallback alias key.
  - `dd-trace-core/src/main/java/datadog/trace/core/tagprocessor/TagsPostProcessorFactory.java:24-28`: installs `QueryObfuscator` with `Config.get().getObfuscationQueryRegexp()`.
  - `dd-trace-core/src/main/java/datadog/trace/core/tagprocessor/QueryObfuscator.java:25-47,60-73`: `null` ⇒ built-in default regex; empty string ⇒ disabled; matches are replaced with `<redacted>` in `http.query` (and `http.url` query part).
- **Inference**: Tunes (or disables) query-string redaction applied to HTTP tags.

### `DD_OPTIMIZED_MAP_ENABLED` (A)

- **Mapping**: `DD_OPTIMIZED_MAP_ENABLED` ↔ `GeneralConfig.OPTIMIZED_MAP_ENABLED` (`"optimized.map.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2956,4745-4746`: reads/exposes the flag (default `true`).
  - `internal-api/src/main/java/datadog/trace/api/TagMap.java:1141-1146`: selects optimized vs legacy `TagMap` implementation at startup.
- **Inference**: Performance/memory toggle controlling which tag-map implementation is used for span tags.

### `DD_OTLP_METRICS_HEADERS` (B)

- **Mapping**: `DD_OTLP_METRICS_HEADERS` ↔ `OtlpConfig.OTLP_METRICS_HEADERS` (`"otlp.metrics.headers"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1909,5178-5179`: parses the value into a headers map via `getMergedMap(..., '=')` and exposes it.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/OtelEnvironmentConfigSource.java:148-162`: when OpenTelemetry metrics exporter is `otlp` (or unset), captures `otlp.metrics.headers` from OTel (`otel.exporter.otlp.metrics.headers`) or Datadog (`dd.otlp.metrics.headers`) sources.
  - `internal-api/src/test/groovy/datadog/trace/api/ConfigTest.groovy:343,457-458`: example format `api-key=key,other-config-value=value`.
- **Inference**: Provides custom HTTP headers to use for OTLP metrics export requests.

### `DD_PIPELINE_EXECUTION_ID` (A)

- **Mapping**: direct CI environment variable used by the CI Visibility AWS CodePipeline provider.
- **Evidence**:
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/ci/AwsCodePipelineInfo.java:12-36`: reads `DD_PIPELINE_EXECUTION_ID` and uses it as `ciPipelineId` (and records it in `_dd.ci.env_vars`).
- **Inference**: Allows CI Visibility to attribute test sessions/spans to a specific AWS CodePipeline execution.

### `DD_PRIMARY_TAG` (A)

- **Mapping**: `DD_PRIMARY_TAG` ↔ `GeneralConfig.PRIMARY_TAG` (`"primary.tag"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1565,4835-4836`: reads/exposes the primary tag string.
  - `internal-api/src/main/java/datadog/trace/api/BaseHash.java:29-35,44-52`: included in the base-hash calculation.
  - `dd-trace-core/src/main/java/datadog/trace/core/datastreams/DefaultDataStreamsMonitoring.java:121-123`: passes `config.getPrimaryTag()` into DSM payload writer.
  - `dd-trace-core/src/main/java/datadog/trace/core/datastreams/MsgPackDatastreamsPayloadWriter.java:62-70,118-121`: serializes it as `PrimaryTag` in DSM payloads.
- **Inference**: Adds a global “primary tag” dimension that affects base hash and DSM aggregation/payloads.

### `DD_PRIORITIZATION_TYPE` (A)

- **Mapping**: `DD_PRIORITIZATION_TYPE` ↔ `TracerConfig.PRIORITIZATION_TYPE` (`"prioritization.type"`).
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/WriterFactory.java:76-82`: reads the `Prioritization` enum (`FAST_LANE` default) and applies it to the remote writer.
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/ddagent/Prioritization.java:13-33,85-103`: `ENSURE_TRACE` blocks to enqueue “kept” traces; `FAST_LANE` prefers dropping under backpressure.
- **Inference**: Controls how the trace writer behaves under backpressure (drop fast vs block to ensure kept traces).

### `DD_PRIORITY_SAMPLING_FORCE` (A)

- **Mapping**: `DD_PRIORITY_SAMPLING_FORCE` ↔ `TracerConfig.PRIORITY_SAMPLING_FORCE` (`"priority.sampling.force"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1542-1543,3175-3176`: reads/exposes the string value.
  - `dd-trace-core/src/main/java/datadog/trace/common/sampling/Sampler.java:83-94`: when priority sampling is enabled, `KEEP` forces `SAMPLER_KEEP`, `DROP` forces `SAMPLER_DROP`, otherwise normal sampling applies.
- **Inference**: Debug/override knob to force a global keep/drop priority decision for traces.

### `DD_PROFILING_AGENTLESS` (A)

- **Mapping**: `DD_PROFILING_AGENTLESS` ↔ `ProfilingConfig.PROFILING_AGENTLESS` (`"profiling.agentless"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2029-2030`: reads the agentless flag.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5107-5127`: when enabled (and `profiling.url` is not set), uses `https://intake.profile.<site>/api/v2/profile` instead of the local Agent endpoint.
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/ProfileUploader.java:151-153`: uploader uses the final profiling URL and records whether it is agentless.
- **Inference**: Switches profile upload to direct-to-intake (agentless) mode rather than Agent-based upload.

### `DD_PROFILING_APIKEY` (A)

- **Mapping**: deprecated legacy API key key `ProfilingConfig.PROFILING_API_KEY_VERY_OLD` (`"profiling.apikey"`) → env var `DD_PROFILING_APIKEY`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:21-24`: marks the key as deprecated and points to `dd.api-key` / `dd.api-key-file`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2052-2056`: if API key is not otherwise set, reads `DD_PROFILING_APIKEY` as a fallback.
- **Inference**: Backward-compatible way to supply an API key for profiling uploads (and other agentless features); prefer `DD_API_KEY`.

### `DD_PROFILING_APIKEY_FILE` (A)

- **Mapping**: deprecated legacy API key file `ProfilingConfig.PROFILING_API_KEY_FILE_VERY_OLD` (`"profiling.apikey.file"`) → env var `DD_PROFILING_APIKEY_FILE`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:23-24`: marks the key as deprecated and points to `dd.api-key-file`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2052-2066`: if API key is not otherwise set, reads the API key from the configured file as a fallback.
- **Inference**: Backward-compatible way to supply an API key via file; prefer `DD_API_KEY_FILE`.

### `DD_PROFILING_API_KEY` (A)

- **Mapping**: deprecated legacy API key key `ProfilingConfig.PROFILING_API_KEY_OLD` (`"profiling.api-key"`) → env var `DD_PROFILING_API_KEY`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:17-20`: marks the key as deprecated and points to `dd.api-key` / `dd.api-key-file`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2038-2041`: if API key is not otherwise set, reads `DD_PROFILING_API_KEY` as a fallback.
- **Inference**: Backward-compatible way to supply an API key for profiling uploads; prefer `DD_API_KEY`.

### `DD_PROFILING_API_KEY_FILE` (A)

- **Mapping**: deprecated legacy API key file `ProfilingConfig.PROFILING_API_KEY_FILE_OLD` (`"profiling.api-key-file"`) → env var `DD_PROFILING_API_KEY_FILE`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:19-20`: marks the key as deprecated and points to `dd.api-key-file`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2038-2049`: if API key is not otherwise set, reads the API key from the configured file as a fallback.
- **Inference**: Backward-compatible way to supply an API key via file; prefer `DD_API_KEY_FILE`.

### `DD_PROFILING_ASYNC_ALLOC_INTERVAL` (A)

- **Mapping**: legacy alias for ddprof allocation interval: `profiling.ddprof.alloc.interval` ↔ normalized legacy key `profiling.async.alloc.interval` (`DD_PROFILING_ASYNC_ALLOC_INTERVAL`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:96-99`: ddprof alloc interval default is `256 * 1024` bytes.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:405-452`: ddprof config keys fall back to `.async.` variants via `normalizeKey()` (`.ddprof.` → `.async.`).
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:321-333`: used as `memory=<interval>b` for allocation/live-heap profiling.
- **Inference**: Controls the bytes-per-sample interval for memory profiling (allocation & live heap) using the legacy `async` naming.

### `DD_PROFILING_ASYNC_CPU_ENABLED` (A)

- **Mapping**: legacy alias for ddprof CPU enablement: `profiling.ddprof.cpu.enabled` ↔ `profiling.async.cpu.enabled`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:99-104`: ddprof CPU enabled defaults to `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:69-73,405-452`: ddprof key is read with fallback to the normalized `.async.` variant.
- **Inference**: Legacy `async` knob to enable/disable CPU profiling in the Datadog native profiler.

### `DD_PROFILING_ASYNC_CPU_INTERVAL_MS` (A)

- **Mapping**: legacy alias for ddprof CPU interval: `profiling.ddprof.cpu.interval.ms` ↔ `profiling.async.cpu.interval.ms`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:102-105`: ddprof CPU interval default is `10` ms.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:88-92,405-452`: ddprof key is read with fallback to the normalized `.async.` variant.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:291-298`: emitted as `cpu=<interval>m` in the ddprof command line.
- **Inference**: Legacy `async` knob controlling CPU sampling interval.

### `DD_PROFILING_ASYNC_CSTACK` (A)

- **Mapping**: legacy alias for ddprof cstack mode: `profiling.ddprof.cstack` ↔ `profiling.async.cstack`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:139-142`: ddprof cstack default is `vm`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:315-326,405-452`: reads the mode and falls back to `.async.` key; on non-HotSpot VMs, `vm*` falls back to `dwarf`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:272-273`: emitted as `cstack=<mode>` in the ddprof command line.
- **Inference**: Legacy `async` knob controlling native stack unwinding mode.

### `DD_PROFILING_ASYNC_DEBUG_LIB` (A)

- **Mapping**: legacy alias for ddprof debug library path: `profiling.ddprof.debug.lib` ↔ `profiling.async.debug.lib`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:93-94`: ddprof debug lib config key.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:76-77,405-452`: reads the ddprof libpath with fallback to the normalized `.async.` variant.
- **Inference**: Legacy `async` knob to point the native profiler to a specific debug library build.

### `DD_PROFILING_ASYNC_LINENUMBERS` (A)

- **Mapping**: legacy alias for ddprof line numbers flag: `profiling.ddprof.linenumbers` ↔ `profiling.async.linenumbers`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:148-151`: ddprof line numbers default is `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:353-358,405-452`: reads the boolean (and falls back to `.async.`); returns `omitLineNumbers`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:276-278`: when line numbers are omitted, appends `linenumbers=f`.
- **Inference**: Legacy `async` knob controlling whether stack traces include line numbers.

### `DD_PROFILING_ASYNC_LIVEHEAP_CAPACITY` (A)

- **Mapping**: legacy alias for ddprof live-heap capacity: `profiling.ddprof.liveheap.capacity` ↔ `profiling.async.liveheap.capacity`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:170-172`: ddprof liveheap capacity default is `1024`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:275-286,405-452`: reads/clamps the capacity (and falls back to `.async.`).
- **Inference**: Legacy `async` knob controlling how many entries the profiler can track for live-heap (memory leak) analysis.

### `DD_PROFILING_ASYNC_LIVEHEAP_ENABLED` (A)

- **Mapping**: legacy alias for ddprof live-heap enablement: `profiling.ddprof.liveheap.enabled` ↔ `profiling.async.liveheap.enabled`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:165-167`: ddprof liveheap enabled default is `false`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:227-239,405-452`: reads the flag (and falls back to `.async.`) and warns when enabled on JVM versions where it is not considered stable.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:321-333`: when enabled, live-heap mode contributes to the ddprof `memory=` configuration.
- **Inference**: Legacy `async` knob to enable/disable live-heap (memory leak) profiling mode.

### `DD_PROFILING_ASYNC_LIVEHEAP_INTERVAL` (A)

- **Mapping**: legacy alias for ddprof live-heap interval: `profiling.ddprof.liveheap.interval` ↔ normalized legacy key `profiling.async.liveheap.interval` (`DD_PROFILING_ASYNC_LIVEHEAP_INTERVAL`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:168-169`: ddprof live-heap interval key.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:260-268`: reads the interval and computes a default from heap size and live-heap capacity (`maxHeap / capacity`; falls back to `1024*1024` when max heap is unknown).
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:450-452`: `.ddprof.` keys fall back to `.async.` variants via `normalizeKey()`.
- **Inference**: Controls the live-heap/memory-leak tracking interval used by the native profiler (legacy `async` naming).

### `DD_PROFILING_ASYNC_LIVEHEAP_SAMPLE_PERCENT` (A)

- **Mapping**: legacy alias for ddprof live-heap sample percent: `profiling.ddprof.liveheap.sample_percent` ↔ normalized legacy key `profiling.async.liveheap.sample_percent`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:176-179`: default is `50`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:253-257`: reads the configured percent.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:328-332`: emits the fraction (`percent/100`) as part of ddprof `memory=` configuration for live-heap mode.
- **Inference**: Sets the sampling rate (as a percentage) used for live-heap profiling in the native profiler (legacy `async` naming).

### `DD_PROFILING_ASYNC_LOGLEVEL` (A)

- **Mapping**: legacy alias for ddprof log level: `profiling.ddprof.loglevel` ↔ normalized legacy key `profiling.async.loglevel`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:136-138`: ddprof log level default is `NONE`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:364-368`: reads the log level with fallback to `.async.` variant.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:268-272,320-321`: passes `loglevel=<value>` to the native profiler command line.
- **Inference**: Controls verbosity of the native profiler (legacy `async` naming).

### `DD_PROFILING_ASYNC_SAFEMODE` (A)

- **Mapping**: legacy alias for ddprof safemode: `profiling.ddprof.safemode` ↔ normalized legacy key `profiling.async.safemode`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:142-146`: defines safemode default (`20`).
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:304-308`: reads the safemode bitmask.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:260-266,273-274`: warns loudly if overridden and passes `safemode=<value>` to the profiler.
- **Inference**: Safety/compatibility bitmask for the native profiler; overriding is risky (legacy `async` naming).

### `DD_PROFILING_ASYNC_WALL_COLLAPSING` (A)

- **Mapping**: legacy alias for ddprof wall collapsing: `profiling.ddprof.wall.collapsing` ↔ normalized legacy key `profiling.async.wall.collapsing`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:114-116`: default is `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:138-142`: reads the flag.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:300-306`: when enabled, uses `wall=~<interval>m`.
- **Inference**: Enables “collapsed” wall-clock stacks for ddprof wall profiling (legacy `async` naming).

### `DD_PROFILING_ASYNC_WALL_CONTEXT_FILTER` (A)

- **Mapping**: legacy alias for ddprof wall context filter: `profiling.ddprof.wall.context.filter` ↔ normalized legacy key `profiling.async.wall.context.filter`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:118-120`: default is `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:156-167`: if tracing is disabled, returns `false`; otherwise reads the flag.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:307-315`: passes `filter=0` when enabled, or `filter=` (disabled) when false.
- **Inference**: Restricts wall-clock sampling to threads with trace context when enabled; disabling samples all threads (legacy `async` naming).

### `DD_PROFILING_ASYNC_WALL_ENABLED` (A)

- **Mapping**: legacy alias for ddprof wall enablement: `profiling.ddprof.wall.enabled` ↔ normalized legacy key `profiling.async.wall.enabled`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:105-107`: ddprof wall profiling enabled default is `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:119-124`: decides default based on ultra-minimal mode, tracing enabled, and JVM vendor (J9).
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:143-145,300-306`: when enabled, WALL mode is active and the command includes `wall=...`.
- **Inference**: Enables wall-clock profiling mode in ddprof (legacy `async` naming).

### `DD_PROFILING_ASYNC_WALL_INTERVAL_MS` (A)

- **Mapping**: legacy alias for ddprof wall interval: `profiling.ddprof.wall.interval.ms` ↔ normalized legacy key `profiling.async.wall.interval.ms`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:108-110`: default is `50` ms.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:127-131`: reads the interval.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:302-306`: passes it as `wall=<interval>m`.
- **Inference**: Controls wall-clock sampling interval for ddprof wall profiling (legacy `async` naming).

### `DD_PROFILING_AUXILIARY` (A)

- **Mapping**: `DD_PROFILING_AUXILIARY` ↔ `ProfilingConfig.PROFILING_AUXILIARY_TYPE` (`"profiling.auxiliary"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:73-74`: default is `none`.
  - `dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerSettingsSupport.java:166-169`: reads the configured auxiliary profiler type.
  - `dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerSettingsSupport.java:261-265`: defaults to `ddprof` when ddprof is enabled, otherwise `none`.
  - `dd-smoke-tests/profiling-integration-tests/src/test/java/datadog/smoketest/SmokeTestUtils.java:40`: example forcing `profiling.auxiliary=async`.
- **Inference**: Selects which auxiliary profiling backend to use (or disables it).

### `DD_PROFILING_BACKPRESSURE_SAMPLING_ENABLED` (A)

- **Mapping**: `DD_PROFILING_BACKPRESSURE_SAMPLING_ENABLED` ↔ `ProfilingConfig.PROFILING_BACKPRESSURE_SAMPLING_ENABLED` (`"profiling.backpressure.sampling.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2114-2117`: reads the boolean into `profilingBackPressureEnabled`.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:264-266`: starts `BackpressureProfiling` when enabled.
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/executor/RejectedExecutionHandlerInstrumentation.java:83`: triggers `BackpressureProfiling.getInstance().process(...)` on rejection/backpressure paths.
  - `dd-java-agent/agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/backpressure/BackpressureProfiling.java:30-34`: commits `BackpressureSampleEvent` when sampled.
  - `dd-java-agent/agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/backpressure/BackpressureSampler.java:18-32`: derives samples/window from the configured sample limit and upload period.
- **Inference**: Enables emission of JFR backpressure sample events (rate-limited) when instrumented code encounters backpressure/rejections.

### `DD_PROFILING_CONTEXT_ATTRIBUTES` (A)

- **Mapping**: `DD_PROFILING_CONTEXT_ATTRIBUTES` ↔ `ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES` (`"profiling.context.attributes"`).
- **Evidence**:
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:375-376`: reads configured context attributes as a set.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:146-153`: builds the final ordered attribute list (configured + optional OPERATION/RESOURCE).
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:274`: passes them to the native profiler as `attributes=...`.
- **Inference**: Defines which context attributes are attached to ddprof samples for trace/profiling correlation.

### `DD_PROFILING_CONTEXT_ATTRIBUTES_RESOURCE_NAME_ENABLED` (A)

- **Mapping**: `DD_PROFILING_CONTEXT_ATTRIBUTES_RESOURCE_NAME_ENABLED` ↔ `ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES_RESOURCE_NAME_ENABLED`.
- **Evidence**:
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:396-398`: default is `false`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:150-152`: when enabled, adds `RESOURCE` to the ddprof attribute list.
- **Inference**: Adds span resource name as a profiling context attribute.

### `DD_PROFILING_CONTEXT_ATTRIBUTES_SPAN_NAME_ENABLED` (A)

- **Mapping**: `DD_PROFILING_CONTEXT_ATTRIBUTES_SPAN_NAME_ENABLED` ↔ `ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES_SPAN_NAME_ENABLED`.
- **Evidence**:
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:392-394`: default is `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:147-149`: when enabled, adds `OPERATION` to the ddprof attribute list.
- **Inference**: Adds span operation name as a profiling context attribute.

### `DD_PROFILING_DDPROF_ALLOC_ENABLED` (A)

- **Mapping**: `DD_PROFILING_DDPROF_ALLOC_ENABLED` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_ENABLED` (`"profiling.ddprof.alloc.enabled"`) (alias of `profiling.allocation.enabled`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:94-95`: ddprof allocation-enabled key.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:192-207`: enables allocation profiling (Java 11+), with warnings when enabled on JVM versions not considered safe.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:132-136`: allocation mode is enabled when allocation profiling is enabled.
- **Inference**: Enables allocation profiling in ddprof (JVMTI allocation sampler).

### `DD_PROFILING_DDPROF_ALLOC_INTERVAL` (A)

- **Mapping**: `DD_PROFILING_DDPROF_ALLOC_INTERVAL` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_INTERVAL` (`"profiling.ddprof.alloc.interval"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:96-99`: default is `256 * 1024`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:216-220`: reads the configured interval.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:321-333`: used in ddprof command as `memory=<interval>b`.
- **Inference**: Controls memory/allocation sampling interval (bytes per sample) for ddprof.

### `DD_PROFILING_DDPROF_CPU_ENABLED` (A)

- **Mapping**: `DD_PROFILING_DDPROF_CPU_ENABLED` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED` (`"profiling.ddprof.cpu.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:99-101`: default is `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:69-73`: reads the flag.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:140-145,279-299`: enables CPU mode and passes cpu scheduling (or custom event) in the command.
- **Inference**: Enables/disables CPU profiling mode for ddprof.

### `DD_PROFILING_DDPROF_CPU_INTERVAL_MS` (A)

- **Mapping**: `DD_PROFILING_DDPROF_CPU_INTERVAL_MS` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_INTERVAL` (`"profiling.ddprof.cpu.interval.ms"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:102-105`: default is `10` ms (`50` ms on J9 when using the default).
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:88-92`: reads the interval.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:291-298`: emits `cpu=<interval>m` (with special handling for J9).
- **Inference**: Controls CPU sampling interval for ddprof.

### `DD_PROFILING_DDPROF_CSTACK` (A)

- **Mapping**: `DD_PROFILING_DDPROF_CSTACK` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK` (`"profiling.ddprof.cstack"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:140-142`: default is `vm`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:315-326`: resolves cstack mode (falls back to `dwarf` on non-HotSpot when `vm*` is requested).
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:270-273`: emits `cstack=<mode>` in the command.
- **Inference**: Controls native stack-walking mode used by ddprof.

### `DD_PROFILING_DDPROF_DEBUG_LIB` (A)

- **Mapping**: `DD_PROFILING_DDPROF_DEBUG_LIB` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH` (`"profiling.ddprof.debug.lib"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:93-94`: ddprof debug lib config key.
  - `dd-java-agent/ddprof-lib/src/main/java/datadog/libs/ddprof/DdprofLibraryLoader.java:130-137`: passes this path into `JavaProfiler.getInstance(...)` to load the profiler library.
- **Inference**: Overrides which ddprof native library is loaded (primarily for debugging/testing).

### `DD_PROFILING_DDPROF_ENABLED` (A)

- **Mapping**: `DD_PROFILING_DDPROF_ENABLED` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED` (`"profiling.ddprof.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2031-2035,3668-3669`: reads/enforces the ddprof enablement flag (also gated on profiling enabled).
  - `dd-java-agent/agent-profiling/src/main/java/com/datadog/profiling/agent/CompositeController.java:148-186`: when enabled (and supported), instantiates `DatadogProfilerController`; when disabled, only JFR controllers are used.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:1351-1362`: enables ddprof-based profiling context labeling when ddprof profiling is enabled.
- **Inference**: Master toggle selecting ddprof as a profiling implementation (vs JFR-only profiling).

### `DD_PROFILING_DDPROF_LINENUMBERS` (A)

- **Mapping**: `DD_PROFILING_DDPROF_LINENUMBERS` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_LINE_NUMBERS` (`"profiling.ddprof.linenumbers"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:148-151`: default is `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:353-358`: turns the boolean into `omitLineNumbers(...)`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:276-278`: when line numbers are omitted, appends `linenumbers=f` to the ddprof command line.
- **Inference**: Enables/disables line numbers in ddprof stack traces.

### `DD_PROFILING_DDPROF_LIVEHEAP_CAPACITY` (A)

- **Mapping**: `DD_PROFILING_DDPROF_LIVEHEAP_CAPACITY` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_CAPACITY` (`"profiling.ddprof.liveheap.capacity"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:170-172`: default is `1024`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:275-286`: reads and clamps the capacity to an upper bound (`8192`), with deprecated alias `profiling.ddprof.memleak.capacity`.
- **Inference**: Controls the maximum size of the live-heap (memory leak) tracking structure.

### `DD_PROFILING_DDPROF_LIVEHEAP_ENABLED` (A)

- **Mapping**: `DD_PROFILING_DDPROF_LIVEHEAP_ENABLED` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED` (`"profiling.ddprof.liveheap.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:165-167`: default is `false`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:227-239`: reads the flag (with deprecated alias `profiling.ddprof.memleak.enabled`) and warns when enabled on JVM versions not considered safe.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:137-139,321-333`: when enabled, MEMLEAK mode contributes to ddprof `memory=` configuration.
- **Inference**: Enables live-heap (memory leak) profiling mode in ddprof.

### `DD_PROFILING_DDPROF_LIVEHEAP_INTERVAL` (A)

- **Mapping**: `DD_PROFILING_DDPROF_LIVEHEAP_INTERVAL` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_INTERVAL` (`"profiling.ddprof.liveheap.interval"`).
- **Evidence**:
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:260-268`: reads the interval and computes a default from heap size and capacity (`maxHeap / capacity`; or `1024*1024` when max heap is unknown). Accepts deprecated alias `profiling.ddprof.memleak.interval`.
- **Inference**: Controls the ddprof live-heap tracking interval parameter (advanced tuning).

### `DD_PROFILING_DDPROF_LIVEHEAP_SAMPLE_PERCENT` (A)

- **Mapping**: `DD_PROFILING_DDPROF_LIVEHEAP_SAMPLE_PERCENT` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_SAMPLE_PERCENT` (`"profiling.ddprof.liveheap.sample_percent"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:176-179`: default is `50`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:253-257`: reads the configured percent.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:328-332`: emits `:<fraction>` where fraction is `percent/100`.
- **Inference**: Sets the live-heap profiling sampling fraction (percentage) used by ddprof.

### `DD_PROFILING_DDPROF_LIVEHEAP_TRACK_SIZE_ENABLED` (A)

- **Mapping**: `DD_PROFILING_DDPROF_LIVEHEAP_TRACK_SIZE_ENABLED` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE` (`"profiling.ddprof.liveheap.track_size.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:173-175`: default is `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:246-250`: reads the flag.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:328-330`: chooses `L` vs `l` mode based on this flag.
- **Inference**: Toggles whether ddprof live-heap profiling tracks heap size (vs a lighter mode).

### `DD_PROFILING_DDPROF_LOGLEVEL` (A)

- **Mapping**: `DD_PROFILING_DDPROF_LOGLEVEL` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_LOG_LEVEL` (`"profiling.ddprof.loglevel"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:136-138`: default is `NONE`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:364-368`: reads the log level.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:268-272,320-321`: passes `loglevel=<value>` to the ddprof command line.
- **Inference**: Controls verbosity of the ddprof native profiler.

### `DD_PROFILING_DDPROF_MEMLEAK_CAPACITY` (A)

- **Mapping**: deprecated alias key `profiling.ddprof.memleak.capacity` → env var `DD_PROFILING_DDPROF_MEMLEAK_CAPACITY`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:161-163`: marks memleak capacity key as deprecated.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:275-286`: accepts it as an alias for live-heap capacity.
- **Inference**: Deprecated alias for `DD_PROFILING_DDPROF_LIVEHEAP_CAPACITY`.

### `DD_PROFILING_DDPROF_MEMLEAK_ENABLED` (A)

- **Mapping**: deprecated alias key `profiling.ddprof.memleak.enabled` → env var `DD_PROFILING_DDPROF_MEMLEAK_ENABLED`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:153-155`: marks memleak enabled key as deprecated.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:227-239`: accepts it as an alias for live-heap enablement.
- **Inference**: Deprecated alias for `DD_PROFILING_DDPROF_LIVEHEAP_ENABLED`.

### `DD_PROFILING_DDPROF_MEMLEAK_INTERVAL` (A)

- **Mapping**: deprecated alias key `profiling.ddprof.memleak.interval` → env var `DD_PROFILING_DDPROF_MEMLEAK_INTERVAL`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:157-159`: marks memleak interval key as deprecated.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:260-268`: accepts it as an alias for live-heap interval.
- **Inference**: Deprecated alias for `DD_PROFILING_DDPROF_LIVEHEAP_INTERVAL`.

### `DD_PROFILING_DDPROF_SAFEMODE` (A)

- **Mapping**: `DD_PROFILING_DDPROF_SAFEMODE` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_SAFEMODE` (`"profiling.ddprof.safemode"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:142-146`: default safemode bitmask is `20`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:304-308`: reads the configured safemode.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:260-266,273-274`: warns loudly when overridden and passes `safemode=<value>` to ddprof.
- **Inference**: Advanced safety/compatibility knob for ddprof; overriding is risky.

### `DD_PROFILING_DDPROF_SCRATCH` (A)

- **Mapping**: `DD_PROFILING_DDPROF_SCRATCH` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_SCRATCH` (`"profiling.ddprof.scratch"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:89-92`: explains scratch directory purpose.
  - `dd-java-agent/ddprof-lib/src/main/java/datadog/libs/ddprof/DdprofLibraryLoader.java:126-137`: passes scratch dir to `JavaProfiler.getInstance(..., scratch)`.
  - `dd-java-agent/ddprof-lib/src/main/java/datadog/libs/ddprof/DdprofLibraryLoader.java:185-196`: default is `<tempDir>/scratch` (created if needed).
- **Inference**: Controls where ddprof extracts/loads native components (filesystem location).

### `DD_PROFILING_DDPROF_STACKDEPTH` (A)

- **Mapping**: `DD_PROFILING_DDPROF_STACKDEPTH` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_STACKDEPTH` (`"profiling.ddprof.stackdepth"`) (alias for `profiling.stackdepth`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:86-87`: general profiling stackdepth default is `512`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:292-297`: reads `profiling.stackdepth` and accepts `profiling.ddprof.stackdepth` as an alias.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:270-273`: passes it as `jstackdepth=<depth>` to ddprof.
- **Inference**: Sets the maximum stack depth captured for ddprof samples.

### `DD_PROFILING_DDPROF_WALL_COLLAPSING` (A)

- **Mapping**: `DD_PROFILING_DDPROF_WALL_COLLAPSING` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_COLLAPSING` (`"profiling.ddprof.wall.collapsing"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:114-116`: default is `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:138-142`: reads the flag.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:300-306`: when enabled, uses `wall=~<interval>m`.
- **Inference**: Enables collapsed wall-clock stacks for ddprof wall profiling.

### `DD_PROFILING_DDPROF_WALL_CONTEXT_FILTER` (A)

- **Mapping**: `DD_PROFILING_DDPROF_WALL_CONTEXT_FILTER` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER` (`"profiling.ddprof.wall.context.filter"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:118-120`: default is `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:156-167`: forces it off when tracing is disabled; otherwise reads the flag.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:307-315`: passes `filter=0` when enabled, or `filter=` when disabled.
- **Inference**: Restricts wall-clock sampling to threads with trace context when enabled; disabling samples all threads.

### `DD_PROFILING_DDPROF_WALL_ENABLED` (A)

- **Mapping**: `DD_PROFILING_DDPROF_WALL_ENABLED` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED` (`"profiling.ddprof.wall.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:105-107`: default is `true`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:119-124`: derives a safe default depending on environment (ultra-minimal, tracing enabled, J9).
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:143-145,300-306`: when enabled, includes `wall=...` in the ddprof command.
- **Inference**: Enables/disables wall-clock profiling mode for ddprof.

### `DD_PROFILING_DDPROF_WALL_INTERVAL_MS` (A)

- **Mapping**: `DD_PROFILING_DDPROF_WALL_INTERVAL_MS` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL` (`"profiling.ddprof.wall.interval.ms"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:108-110`: default is `50` ms.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:127-131`: reads the configured interval.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:302-306`: passes it as `wall=<interval>m`.
- **Inference**: Controls wall-clock sampling interval for ddprof.

### `DD_PROFILING_DEBUG_DUMP_PATH` (A)

- **Mapping**: `DD_PROFILING_DEBUG_DUMP_PATH` ↔ `ProfilingConfig.PROFILING_DEBUG_DUMP_PATH` (`"profiling.debug.dump_path"`).
- **Evidence**:
  - `dd-java-agent/agent-profiling/src/main/java/com/datadog/profiling/agent/ProfilingAgent.java:135-156`: when set, wraps the uploader with a `DataDumper` that writes recordings to disk before uploading.
  - `dd-java-agent/agent-profiling/src/main/java/com/datadog/profiling/agent/ProfilingAgent.java:52-85`: `DataDumper` creates the directory if needed and writes `.jfr` dumps.
- **Inference**: Debug feature to persist profiles locally as `.jfr` files.

### `DD_PROFILING_DEBUG_JFR_DISABLED` (A)

- **Mapping**: `DD_PROFILING_DEBUG_JFR_DISABLED` ↔ `ProfilingConfig.PROFILING_DEBUG_JFR_DISABLED` (`"profiling.debug.jfr.disabled"`).
- **Evidence**:
  - `dd-java-agent/agent-profiling/src/main/java/com/datadog/profiling/agent/CompositeController.java:149-166`: disables selection/initialization of JFR controllers when set.
- **Inference**: Forces profiling to avoid JFR-based controllers (debug/troubleshooting knob).

### `DD_PROFILING_DEBUG_UPLOAD_COMPRESSION` (A)

- **Mapping**: `DD_PROFILING_DEBUG_UPLOAD_COMPRESSION` ↔ `ProfilingConfig.PROFILING_DEBUG_UPLOAD_COMPRESSION` (`"profiling.debug.upload.compression"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2097-2101,3612-3613`: reads/exposes the configured compression type (falls back to deprecated `profiling.upload.compression`).
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:205-214`: documents supported values (`on/off/lz4/gzip/zstd`, with `on` ≈ `zstd`).
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/ProfileUploader.java:214-215`: uses it to choose request compression.
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/CompressionType.java:21-41`: parses the configured value.
- **Inference**: Controls how profile payloads are compressed during upload.

### `DD_PROFILING_DETAILED_DEBUG_LOGGING` (A)

- **Mapping**: `DD_PROFILING_DETAILED_DEBUG_LOGGING` ↔ `ProfilingConfig.PROFILING_DETAILED_DEBUG_LOGGING` (`"profiling.detailed.debug.logging"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:264-265`: default is `false`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:123-125,426-430`: reads the flag and, when enabled, logs `localRootSpanId=...` with a stack trace (new `Throwable`) for debug purposes.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:360-375`: debug logging runs when setting/clearing span context (`setSpanContext`, `clearSpanContext`).
- **Inference**: Debug knob to help troubleshoot ddprof context labeling/correlation by emitting stack-trace debug logs.

### `DD_PROFILING_DIRECTALLOCATION_ENABLED` (A)

- **Mapping**: `DD_PROFILING_DIRECTALLOCATION_ENABLED` ↔ `ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED` (`"profiling.directallocation.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:82-84`: default is `false`.
  - `dd-java-agent/instrumentation/java/java-nio-1.8/src/main/java/datadog/trace/instrumentation/directbytebuffer/ByteBufferInstrumentation.java:27-34`: enables the instrumentation only when the flag is true (and Java 11+ and JFR available).
  - `dd-java-agent/instrumentation/java/java-nio-1.8/src/main/java11/datadog/trace/instrumentation/directbytebuffer/AllocateDirectAdvice.java:13-33`: emits `DirectAllocationSampleEvent` for `ByteBuffer.allocateDirect`.
  - `dd-java-agent/instrumentation/java/java-nio-1.8/src/main/java11/datadog/trace/instrumentation/directbytebuffer/MemoryMappingAdvice.java:13-33`: emits events for `FileChannel.map` (mmap allocations).
- **Inference**: Enables JFR-based direct allocation profiling (direct buffers / memory mappings).

### `DD_PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT` (A)

- **Mapping**: `DD_PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT` ↔ `ProfilingConfig.PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT` (`"profiling.direct.allocation.sample.limit"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:61-63`: default is `2000`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2118-2121,3636-3637`: reads/exposes the integer.
  - `dd-java-agent/agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/directallocation/DirectAllocationSampler.java:19-30`: converts the limit into a per-window sample budget based on upload period.
  - `dd-java-agent/agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/directallocation/DirectAllocationProfiling.java:38-45`: commits an event when sampled or on first hit for a caller/source/bytes bucket.
- **Inference**: Budget controlling how many direct-allocation JFR sample events are emitted per profile recording/upload period.

### `DD_PROFILING_DISABLED_EVENTS` (A)

- **Mapping**: `DD_PROFILING_DISABLED_EVENTS` ↔ `ProfilingConfig.PROFILING_DISABLED_EVENTS` (`"profiling.disabled.events"`).
- **Evidence**:
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:179-184`: reads a comma-separated list and applies `disableEvent(recordingSettings, <event>, ...)`.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:319-325`: implements disable by setting `<event>#enabled=false` in JFR recording settings.
- **Inference**: Lets users force-disable specific JFR events by name.

### `DD_PROFILING_ENABLED_EVENTS` (A)

- **Mapping**: `DD_PROFILING_ENABLED_EVENTS` ↔ `ProfilingConfig.PROFILING_ENABLED_EVENTS` (`"profiling.enabled.events"`).
- **Evidence**:
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:186-191`: reads a comma-separated list and applies `enableEvent(recordingSettings, <event>, ...)`.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:327-333`: implements enable by setting `<event>#enabled=true`.
- **Inference**: Lets users force-enable specific JFR events by name.

### `DD_PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE` (A)

- **Mapping**: `DD_PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE` ↔ `ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE` (`"profiling.exception.histogram.max-collection-size"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:67-69`: default is `10000`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2126-2129,3652-3653`: reads/exposes the limit.
  - `dd-java-agent/agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/exceptions/ExceptionHistogram.java:37-39,67-71`: caps the number of tracked types; overflows are recorded under `TOO-MANY-EXCEPTIONS`.
- **Inference**: Memory/cardinality bound for exception histogram tracking.

### `DD_PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS` (A)

- **Mapping**: `DD_PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS` ↔ `ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS` (`"profiling.exception.histogram.top-items"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:64-66`: default is `50`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2122-2125,3648-3649`: reads/exposes the configured top-N.
  - `dd-java-agent/agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/exceptions/ExceptionHistogram.java:98-103`: limits emitted exception-count events to top-N.
- **Inference**: Controls how many exception types are reported per histogram emit (top-N).

### `DD_PROFILING_EXCEPTION_RECORD_MESSAGE` (A)

- **Mapping**: `DD_PROFILING_EXCEPTION_RECORD_MESSAGE` ↔ `ProfilingConfig.PROFILING_EXCEPTION_RECORD_MESSAGE` (`"profiling.exception.record.message"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:51-53`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2133-2135,3665`: reads/exposes the boolean.
  - `dd-java-agent/agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/exceptions/ExceptionProfiling.java:83-88`: wires the boolean into exception profiling.
  - `dd-java-agent/agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/exceptions/ExceptionSampleEvent.java:47-56`: conditionally reads `Throwable.getMessage()` for the JFR event field.
- **Inference**: Toggles whether exception sample events include the exception message.

### `DD_PROFILING_EXCEPTION_SAMPLE_LIMIT` (A)

- **Mapping**: `DD_PROFILING_EXCEPTION_SAMPLE_LIMIT` ↔ `ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT` (`"profiling.exception.sample.limit"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:49-50`: default is `10000`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2108-2110,3632-3633`: reads/exposes the integer.
  - `dd-java-agent/agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/exceptions/ExceptionSampler.java:18-32`: converts the limit into a per-window sample budget based on upload period.
  - `dd-java-agent/agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/exceptions/ExceptionProfiling.java:105-113`: always records first occurrences; otherwise samples based on the sampler budget.
- **Inference**: Budget controlling how many exception sample JFR events are emitted per profile recording/upload period.

### `DD_PROFILING_EXCLUDE_AGENT_THREADS` (A)

- **Mapping**: `DD_PROFILING_EXCLUDE_AGENT_THREADS` ↔ `ProfilingConfig.PROFILING_EXCLUDE_AGENT_THREADS` (`"profiling.exclude.agent-threads"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2131,3656-3657`: reads/exposes the boolean (default `true`).
  - `dd-java-agent/instrumentation/datadog/profiling/exception-profiling/src/main/java11/datadog/exceptions/instrumentation/ThrowableInstanceAdvice.java:40-43`: skips exception profiling on agent thread group when enabled.
- **Inference**: Reduces noise/overhead by excluding internal tracer threads from exception profiling.

### `DD_PROFILING_EXPERIMENTAL_ASYNC_SCHEDULING_EVENT` (A)

- **Mapping**: legacy alias of ddprof scheduling-event key: `profiling.experimental.async.scheduling.event` is used as a fallback for `profiling.experimental.ddprof.scheduling.event`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:126-130`: ddprof scheduling event keys.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:405-452`: ddprof config reads fall back to `.async.` variants via `normalizeKey()` (`.ddprof.` → `.async.`).
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:281-288`: when set, emits `event=<name>` (and optional `interval=`) in the ddprof command.
- **Inference**: Backward-compatible alias to configure a custom CPU scheduling event for ddprof.

### `DD_PROFILING_EXPERIMENTAL_ASYNC_SCHEDULING_EVENT_INTERVAL` (A)

- **Mapping**: legacy alias of ddprof scheduling-event interval: `profiling.experimental.async.scheduling.event.interval` as fallback for `profiling.experimental.ddprof.scheduling.event.interval`.
- **Evidence**:
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:423-426,450-452`: integer reads fall back to normalized `.async.` key.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:285-288`: interval is appended only when `> 0`.
- **Inference**: Backward-compatible alias to set the sampling interval for a custom scheduling event.

### `DD_PROFILING_EXPERIMENTAL_ASYNC_WALL_JVMTI` (A)

- **Mapping**: legacy alias of ddprof wall JVMTI flag: `profiling.experimental.async.wall.jvmti` as fallback for `profiling.experimental.ddprof.wall.jvmti`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:122-124`: ddprof wall JVMTI flag default is `false`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:443-452`: uses `normalizeKey()` fallback for the flag.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:316-318`: when enabled, appends `wallsampler=jvmti`.
- **Inference**: Backward-compatible alias to switch ddprof wall-clock sampling to a JVMTI-based sampler.

### `DD_PROFILING_EXPERIMENTAL_DDPROF_SCHEDULING_EVENT` (A)

- **Mapping**: `DD_PROFILING_EXPERIMENTAL_DDPROF_SCHEDULING_EVENT` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_SCHEDULING_EVENT` (`"profiling.experimental.ddprof.scheduling.event"`).
- **Evidence**:
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:99-104`: reads the event name.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:281-299`: when set, uses `event=<name>` (and optional `interval=`) instead of `cpu=<interval>m`.
- **Inference**: Advanced knob to run CPU profiling based on a specific hardware/perf event instead of CPU time sampling.

### `DD_PROFILING_EXPERIMENTAL_DDPROF_SCHEDULING_EVENT_INTERVAL` (A)

- **Mapping**: `DD_PROFILING_EXPERIMENTAL_DDPROF_SCHEDULING_EVENT_INTERVAL` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_SCHEDULING_EVENT_INTERVAL` (`"profiling.experimental.ddprof.scheduling.event.interval"`).
- **Evidence**:
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:107-113`: reads the interval integer.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:285-288`: only appends the interval when `> 0`.
- **Inference**: Optional interval for the custom ddprof scheduling event.

### `DD_PROFILING_EXPERIMENTAL_DDPROF_WALL_JVMTI` (A)

- **Mapping**: `DD_PROFILING_EXPERIMENTAL_DDPROF_WALL_JVMTI` ↔ `ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_JVMTI` (`"profiling.experimental.ddprof.wall.jvmti"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:122-124`: default is `false`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:443-447`: reads the boolean via `useJvmtiWallclockSampler(...)`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:316-318`: appends `wallsampler=jvmti` when enabled.
- **Inference**: Experimental knob to switch ddprof wall-clock sampling implementation to JVMTI.

### `DD_PROFILING_HEAP_HISTOGRAM_MODE` (A)

- **Mapping**: `DD_PROFILING_HEAP_HISTOGRAM_MODE` ↔ `ProfilingConfig.PROFILING_HEAP_HISTOGRAM_MODE` (`"profiling.heap.histogram.mode"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:254-255`: default is `aftergc`.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:133-148`: when heap histogram is enabled, `periodic` enables `jdk.ObjectCount`, otherwise enables `jdk.ObjectCountAfterGC`.
- **Inference**: Selects periodic vs after-GC heap histogram collection mode (only when heap histogram is enabled).

### `DD_PROFILING_HEAP_TRACK_GENERATIONS` (A)

- **Mapping**: `DD_PROFILING_HEAP_TRACK_GENERATIONS` ↔ `ProfilingConfig.PROFILING_HEAP_TRACK_GENERATIONS` (`"profiling.heap.track.generations"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:257-258`: default is `false`.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:400-403`: reads the boolean.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:274-276`: passes it to ddprof as `generations=<bool>`.
- **Inference**: Toggles ddprof heap profiling generation tracking.

### `DD_PROFILING_HOTSPOTS_ENABLED` (A)

- **Mapping**: `DD_PROFILING_HOTSPOTS_ENABLED` ↔ `ProfilingConfig.PROFILING_HOTSPOTS_ENABLED` (`"profiling.hotspots.enabled"`).
- **Evidence**:
  - `dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerSettingsSupport.java:161`: reads the boolean (default `false`) into `hotspotsEnabled`.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/JfrProfilerSettings.java:58-60`: publishes the setting.
  - `dd-java-agent/agent-profiling/profiling-controller-ddprof/src/main/java/com/datadog/profiling/controller/ddprof/DatadogProfilerSettings.java:26-28`: publishes the setting for ddprof.
- **Inference**: Feature-flag-style knob; in this codebase it is recorded/published but no further behavioral use was found.

### `DD_PROFILING_JFR_REPOSITORY_BASE` (A)

- **Mapping**: `DD_PROFILING_JFR_REPOSITORY_BASE` ↔ `ProfilingConfig.PROFILING_JFR_REPOSITORY_BASE` (`"profiling.jfr.repository.base"`) (deprecated).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:76-78`: legacy default is `${java.io.tmpdir}/dd/jfr`.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:274-284`: reads the legacy value and warns if set to non-default, instructing to use `profiling.tempdir`.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:285-296`: ignores the legacy value and always uses `TempLocationManager.getTempDir()/jfr`.
- **Inference**: Deprecated setting that no longer changes the repository location; use `DD_PROFILING_TEMP_DIR` instead.

### `DD_PROFILING_JFR_REPOSITORY_MAXSIZE` (A)

- **Mapping**: `DD_PROFILING_JFR_REPOSITORY_MAXSIZE` ↔ `ProfilingConfig.PROFILING_JFR_REPOSITORY_MAXSIZE` (`"profiling.jfr.repository.maxsize"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:185-187`: default is `64 * 1024 * 1024` (64MB).
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:299-316`: reads this value and passes it as `maxSize` when creating an `OpenJdkOngoingRecording`.
- **Inference**: Controls the size limit of the JFR repository used by the profiling recording.

### `DD_PROFILING_JFR_TEMPLATE_OVERRIDE_FILE` (A)

- **Mapping**: `DD_PROFILING_JFR_TEMPLATE_OVERRIDE_FILE` ↔ `ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE` (`"profiling.jfr-template-override-file"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:32-33`: config key name.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:162-168`: reads the file path and applies it via `JfpUtils.readOverrideJfpResource(...)`.
  - `dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerSettingsSupport.java:148`: records/publishes the configured value.
- **Inference**: Allows overriding the base JFR recording template (`.jfp`) with user-provided settings.

### `DD_PROFILING_PROXY_HOST` (A)

- **Mapping**: `DD_PROFILING_PROXY_HOST` ↔ `ProfilingConfig.PROFILING_PROXY_HOST` (`"profiling.proxy.host"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2102,3616-3617`: reads/exposes the host.
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/ProfileUploader.java:201-212`: passes proxy host/port/user/pass into `OkHttpUtils.buildHttpClient(...)`.
- **Inference**: Configures an HTTP proxy for profile uploads.

### `DD_PROFILING_PROXY_PASSWORD` (A)

- **Mapping**: `DD_PROFILING_PROXY_PASSWORD` ↔ `ProfilingConfig.PROFILING_PROXY_PASSWORD` (`"profiling.proxy.password"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2106,3628`: reads/exposes the password.
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/ProfileUploader.java:201-212`: provided to the uploader HTTP client builder.
- **Inference**: Proxy authentication password for profile uploads.

### `DD_PROFILING_PROXY_PORT` (A)

- **Mapping**: `DD_PROFILING_PROXY_PORT` ↔ `ProfilingConfig.PROFILING_PROXY_PORT` (`"profiling.proxy.port"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:45-46`: default is `8080`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2103-2104,3620-3621`: reads/exposes the port.
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/ProfileUploader.java:201-212`: passed into `OkHttpUtils.buildHttpClient(...)`.
- **Inference**: Proxy port for profile uploads.

### `DD_PROFILING_PROXY_USERNAME` (A)

- **Mapping**: `DD_PROFILING_PROXY_USERNAME` ↔ `ProfilingConfig.PROFILING_PROXY_USERNAME` (`"profiling.proxy.username"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2105,3624-3625`: reads/exposes the username.
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/ProfileUploader.java:201-212`: passed into the uploader HTTP client builder.
- **Inference**: Proxy authentication username for profile uploads.

### `DD_PROFILING_QUEUEING_TIME_ENABLED` (A)

- **Mapping**: `DD_PROFILING_QUEUEING_TIME_ENABLED` ↔ `ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED` (`"profiling.queueing.time.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:230-232`: default is `true`.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:151-158`: when enabled, sets `datadog.QueueTime#threshold` recording setting.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/JFREventContextIntegration.java:35-40,87-93`: when enabled, creates `QueueTimeEvent` timings for queueing timers.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilingIntegration.java:28-33,131-133`: ddprof integration gates queue timing on the flag.
- **Inference**: Master toggle enabling/disabling queue time profiling.

### `DD_PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS` (A)

- **Mapping**: `DD_PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS` ↔ `ProfilingConfig.PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS` (`"profiling.queueing.time.threshold.millis"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:244-247`: default is `50` ms.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:153-158`: passed as `datadog.QueueTime#threshold` for JFR queue time events.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfiler.java:154-157,458-460`: ddprof only records a queue time event when elapsed time exceeds this threshold.
- **Inference**: Minimum queueing duration required to emit queue time events.

### `DD_PROFILING_SMAP_AGGREGATION_ENABLED` (A)

- **Mapping**: `DD_PROFILING_SMAP_AGGREGATION_ENABLED` ↔ `ProfilingConfig.PROFILING_SMAP_AGGREGATION_ENABLED` (`"profiling.smap.aggregation.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:239-242`: default is `false`.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:227-238`: enables/disables `datadog.AggregatedSmapEntry` periodic event based on the flag.
- **Inference**: Toggles aggregated smaps collection.

### `DD_PROFILING_SMAP_COLLECTION_ENABLED` (A)

- **Mapping**: `DD_PROFILING_SMAP_COLLECTION_ENABLED` ↔ `ProfilingConfig.PROFILING_SMAP_COLLECTION_ENABLED` (`"profiling.smap.collection.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:234-237`: default is `false`.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:218-226`: enables/disables `datadog.SmapEntry` periodic event based on the flag.
- **Inference**: Toggles smaps collection.

### `DD_PROFILING_STACKDEPTH` (A)

- **Mapping**: `DD_PROFILING_STACKDEPTH` ↔ `ProfilingConfig.PROFILING_STACKDEPTH` (`"profiling.stackdepth"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:86-87`: default is `512`.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:339-342`: reads this value for JFR recording configuration.
  - `dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerSettingsSupport.java:178-181`: captures the requested stack depth for settings reporting.
- **Inference**: Controls stack depth captured for profiling stack traces.

### `DD_PROFILING_START_DELAY` (A)

- **Mapping**: `DD_PROFILING_START_DELAY` ↔ `ProfilingConfig.PROFILING_START_DELAY` (`"profiling.start-delay"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:26-27`: default is `10` seconds.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2070-2091,3592-3593`: reads the delay; for AUTO/INJECTED enablement it forces the default delay.
  - `dd-java-agent/agent-profiling/src/main/java/com/datadog/profiling/agent/ProfilingAgent.java:140-162`: uses it to build `startupDelay` for the profiling system.
- **Inference**: Delays profiling start after tracer startup.

### `DD_PROFILING_START_FORCE_FIRST` (A)

- **Mapping**: `DD_PROFILING_START_FORCE_FIRST` ↔ `ProfilingConfig.PROFILING_START_FORCE_FIRST` (`"profiling.start-force-first"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:28-29`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2072-2091`: in AUTO/INJECTED enablement, forcing first is ignored (defaults used).
  - `dd-java-agent/agent-profiling/src/main/java/com/datadog/profiling/agent/ProfilingAgent.java:103-118`: uses it (or forces it in native image) to decide whether to start profiling early; may delay if unsafe.
- **Inference**: Attempts to start profiling immediately at JVM startup (premain), when safe.

### `DD_PROFILING_TAGS` (A)

- **Mapping**: `DD_PROFILING_TAGS` ↔ `ProfilingConfig.PROFILING_TAGS` (`"profiling.tags"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2069,4890-4907`: reads a merged map of profiling tags and merges them with global/runtime tags for profiles.
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/ProfileUploader.java:164-170`: includes merged profiling tags in the upload payload.
- **Inference**: Adds user-provided tags to profiling payloads.

### `DD_PROFILING_TEMPDIR` (B)

- **Mapping**: `DD_PROFILING_TEMPDIR` ↔ `ProfilingConfig.PROFILING_TEMP_DIR` (`"profiling.tempdir"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:192-193`: default is `java.io.tmpdir`.
  - `internal-api/src/main/java/datadog/trace/util/TempLocationManager.java:274-285`: base temp dir is read from config, must exist, otherwise profiling fails to initialize temp location manager.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:285-296`: creates JFR repository under the per-process temp dir (`<tempdir>/jfr`).
- **Inference**: Controls where profiling temporary directories are created; must point to an existing directory.

### `DD_PROFILING_TIMELINE_EVENTS_ENABLED` (A)

- **Mapping**: `DD_PROFILING_TIMELINE_EVENTS_ENABLED` ↔ `ProfilingConfig.PROFILING_TIMELINE_EVENTS_ENABLED` (`"profiling.timeline.events.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:260-262`: default is `true`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:1363-1374`: only attempts to load `JFREventContextIntegration` when this flag is enabled.
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/JFREventContextIntegration.java:30-66`: gates `TimelineEvent` creation on this flag.
- **Inference**: Enables/disables JFR timeline events for profiling context integration.

### `DD_PROFILING_ULTRA_MINIMAL` (A)

- **Mapping**: `DD_PROFILING_ULTRA_MINIMAL` ↔ `ProfilingConfig.PROFILING_ULTRA_MINIMAL` (`"profiling.ultra.minimal"`).
- **Evidence**:
  - `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java:99-106`: selects a safer JFR template (`SAFEPOINTS_JFP`) in ultra-minimal mode.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java:241-245`: disables profiling instrumentations when ultra-minimal is enabled.
  - `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java:119-124`: uses ultra-minimal to decide the default enablement of ddprof wall profiling.
- **Inference**: “Safe/low-overhead” profiling mode that reduces enabled profiling features/instrumentations.

### `DD_PROFILING_UPLOAD_COMPRESSION` (A)

- **Mapping**: deprecated key `ProfilingConfig.PROFILING_UPLOAD_COMPRESSION` (`"profiling.upload.compression"`), used as a fallback for `ProfilingConfig.PROFILING_DEBUG_UPLOAD_COMPRESSION`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:41-42`: marks the key as deprecated.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2097-2101`: reads `profiling.debug.upload.compression` and falls back to `profiling.upload.compression`.
  - `dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerSettingsSupport.java:131-136`: explicitly documents the fallback behavior.
- **Inference**: Deprecated upload compression setting; prefer `DD_PROFILING_DEBUG_UPLOAD_COMPRESSION`.

### `DD_PROFILING_UPLOAD_PERIOD` (A)

- **Mapping**: `DD_PROFILING_UPLOAD_PERIOD` ↔ `ProfilingConfig.PROFILING_UPLOAD_PERIOD` (`"profiling.upload.period"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:30-31`: default is `60` seconds.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2092-2093,3600-3601`: reads/exposes the upload period.
  - `dd-java-agent/agent-profiling/src/main/java/com/datadog/profiling/agent/ProfilingAgent.java:140-161`: uses it to set upload period and randomize startup delay.
  - `dd-java-agent/agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/exceptions/ExceptionSampler.java:29-32`: uses upload period to derive per-window sampling budget.
- **Inference**: Sets how often profile recordings are uploaded and influences per-recording sampling calculations.

### `DD_PROFILING_UPLOAD_SUMMARY_ON_413` (A)

- **Mapping**: `DD_PROFILING_UPLOAD_SUMMARY_ON_413` ↔ `ProfilingConfig.PROFILING_UPLOAD_SUMMARY_ON_413` (`"profiling.upload.summary-on-413"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:189-190`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2137-2139`: reads the boolean into config.
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/ProfileUploader.java:349-353`: if upload fails with HTTP 413 and this is enabled, dumps a profile summary via `JfrCliHelper.invokeOn(...)`.
- **Inference**: Debug knob to log a profile summary when uploads are rejected for being too large (413).

### `DD_PROFILING_UPLOAD_TIMEOUT` (B)

- **Mapping**: `DD_PROFILING_UPLOAD_TIMEOUT` ↔ `ProfilingConfig.PROFILING_UPLOAD_TIMEOUT` (`"profiling.upload.timeout"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java:34-35`: default is `30` seconds.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2095-2096,3608-3609`: reads/exposes the timeout (seconds).
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/ProfileUploader.java:189`: converts it to a `Duration` for the uploader.
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/ProfileUploader.java:201-212`: used when building the HTTP client for uploads.
- **Inference**: Controls how long the profiler uploader waits for upload HTTP requests.

### `DD_PROFILING_URL` (A)

- **Mapping**: `DD_PROFILING_URL` ↔ `ProfilingConfig.PROFILING_URL` (`"profiling.url"`) (deprecated in favor of `dd.site`/agentless settings, but still supported).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2036`: reads `profilingUrl`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5107-5127`: `getFinalProfilingUrl()` uses `profilingUrl` if set; otherwise selects agentless intake vs Datadog Agent endpoint.
  - `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/ProfileUploader.java:151-155`: uploader targets `config.getFinalProfilingUrl()`.
- **Inference**: Overrides where profiles are uploaded.

### `DD_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED` (A)

- **Mapping**: `DD_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED` ↔ `TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED` (`"propagation.extract.log_header_names.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1732-1735,3351-3352`: reads/exposes `logExtractHeaderNames` (default `false`).
  - `dd-trace-core/src/main/java/datadog/trace/core/propagation/ContextInterpreter.java:66`: caches the flag as `LOG_EXTRACT_HEADER_NAMES`.
  - `dd-trace-core/src/main/java/datadog/trace/core/propagation/DatadogHttpCodec.java:122-124`: when enabled, logs `Header: <name>` at debug while extracting.
- **Inference**: Debug knob to log incoming header names during propagation extraction.

### `DD_RABBIT_INCLUDE_ROUTINGKEY_IN_RESOURCE` (A)

- **Mapping**: `DD_RABBIT_INCLUDE_ROUTINGKEY_IN_RESOURCE` ↔ `TraceInstrumentationConfig.RABBIT_INCLUDE_ROUTINGKEY_IN_RESOURCE` (`"rabbit.include.routingkey.in.resource"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2661-2662,4499-4501`: reads/exposes the boolean (default `true`).
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitDecorator.java:180-191`: when enabled, appends ` -> <routingKey>` to the `basic.publish` span resource name.
- **Inference**: Controls whether RabbitMQ publish spans include routing key in the resource name.

### `DD_RABBIT_PROPAGATION_DISABLED_EXCHANGES` (A)

- **Mapping**: `DD_RABBIT_PROPAGATION_DISABLED_EXCHANGES` ↔ `TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_EXCHANGES` (`"rabbit.propagation.disabled.exchanges"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2659-2660`: reads the list into a set.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:4493-4496`: `isRabbitPropagationDisabledForDestination()` checks membership in the exchanges set (and the queues set).
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitChannelInstrumentation.java:181-211`: skips header injection for `basic.publish` when destination is disabled.
- **Inference**: Disables trace-context propagation for specified RabbitMQ exchanges.

### `DD_RABBIT_PROPAGATION_DISABLED_QUEUES` (A)

- **Mapping**: `DD_RABBIT_PROPAGATION_DISABLED_QUEUES` ↔ `TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_QUEUES` (`"rabbit.propagation.disabled.queues"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2657-2658`: reads the list into a set.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:4493-4496`: `isRabbitPropagationDisabledForDestination()` checks membership in the queues set (and the exchanges set).
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitChannelInstrumentation.java:255-265`: disables header extraction for `basic.get` when queue is disabled.
- **Inference**: Disables trace-context propagation for specified RabbitMQ queues.

### `DD_RC_TARGETS_KEY` (A)

- **Mapping**: `DD_RC_TARGETS_KEY` ↔ `RemoteConfigConfig.REMOTE_CONFIG_TARGETS_KEY` (`"rc.targets.key"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/RemoteConfigConfig.java:16-17`: remote config targets key names.
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:200-203`: default Datadog-provided key id/key.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2504-2505,4272-4273`: reads/exposes the key string.
  - `remote-config/remote-config-core/src/main/java/datadog/remoteconfig/DefaultConfigurationPoller.java:119-125`: parses it as a hex-encoded Ed25519 public key used by the poller.
- **Inference**: Configures the public key used to verify remote config TUF targets metadata.

### `DD_RC_TARGETS_KEY_ID` (A)

- **Mapping**: `DD_RC_TARGETS_KEY_ID` ↔ `RemoteConfigConfig.REMOTE_CONFIG_TARGETS_KEY_ID` (`"rc.targets.key.id"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/RemoteConfigConfig.java:16-17`: key names.
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:200-203`: default key id/key.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2501-2503,4268-4269`: reads/exposes key id.
  - `remote-config/remote-config-core/src/main/java/datadog/remoteconfig/DefaultConfigurationPoller.java:119-120`: uses it as the key id for verification.
- **Inference**: Identifies which key to use when verifying remote config TUF targets.

### `DD_REMOTE_CONFIG_ENABLED` (A)

- **Mapping**: `DD_REMOTE_CONFIG_ENABLED` ↔ `RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED` (`"remote_configuration.enabled"`) (with deprecated alias `remote_config.enabled`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:196`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2487-2489,4252-4254`: reads/exposes the boolean (also accepts deprecated alias).
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:273-275,754-777`: if enabled, starts the remote config poller.
- **Inference**: Master toggle enabling remote config polling.

### `DD_REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED` (A)

- **Mapping**: `DD_REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED` ↔ `RemoteConfigConfig.REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED` (`"remote_config.integrity_check.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:197`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2490-2492,4256-4258`: reads/exposes the boolean.
  - `remote-config/remote-config-core/src/main/java/datadog/remoteconfig/DefaultConfigurationPoller.java:132-133`: stores the flag as `integrityChecks` for remote config processing.
- **Inference**: Enables extra integrity checks for remote config processing.

### `DD_REMOTE_CONFIG_MAX_EXTRA_SERVICES` (A)

- **Mapping**: `DD_REMOTE_CONFIG_MAX_EXTRA_SERVICES` ↔ `RemoteConfigConfig.REMOTE_CONFIG_MAX_EXTRA_SERVICES` (`"remote_config.max_extra_services"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:204`: default is `64`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2507-2509,4276-4278`: reads/exposes the integer.
  - `internal-api/src/main/java/datadog/trace/api/remoteconfig/ServiceNameCollector.java:19-53`: caps collected extra services to this maximum and drops additional services once reached.
  - `remote-config/remote-config-core/src/main/java/datadog/remoteconfig/PollerRequestFactory.java:114-139`: includes `extraServices` in remote config request.
- **Inference**: Caps the number of service names sent in remote config requests.

### `DD_REMOTE_CONFIG_MAX_PAYLOAD_SIZE` (A)

- **Mapping**: `DD_REMOTE_CONFIG_MAX_PAYLOAD_SIZE` ↔ `RemoteConfigConfig.REMOTE_CONFIG_MAX_PAYLOAD_SIZE` (`"remote_config.max.payload.size"`) (in KiB).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:198`: default is `5120` KiB.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2497-2500,4248-4250`: reads and converts to bytes (`* 1024`).
  - `remote-config/remote-config-core/src/main/java/datadog/remoteconfig/DefaultConfigurationPoller.java:131-133,370-372`: enforces max payload size with `SizeCheckedInputStream`.
- **Inference**: Protects the tracer from overly large remote config responses.

### `DD_REMOTE_CONFIG_URL` (A)

- **Mapping**: `DD_REMOTE_CONFIG_URL` ↔ `RemoteConfigConfig.REMOTE_CONFIG_URL` (`"remote_config.url"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2493,4260-4262`: reads/exposes the URL if configured.
  - `remote-config/remote-config-core/src/main/java/datadog/remoteconfig/DefaultConfigurationPoller.java:266-281`: delays initialization until the URL is available (from a supplier).
  - `communication/src/main/java/datadog/communication/ddagent/SharedCommunicationObjects.java:225-234`: discovers and caches the remote config endpoint URL from the Datadog Agent features discovery when not explicitly set.
- **Inference**: Overrides the remote config endpoint the tracer polls.

### `DD_RESILIENCE4J_MEASURED_ENABLED` (A)

- **Mapping**: `DD_RESILIENCE4J_MEASURED_ENABLED` ↔ `TraceInstrumentationConfig.RESILIENCE4J_MEASURED_ENABLED` (`"resilience4j.measured.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2698-2700`: reads the boolean (default `false`).
  - `dd-java-agent/instrumentation/resilience4j/resilience4j-2.0/src/main/java/datadog/trace/instrumentation/resilience4j/Resilience4jSpanDecorator.java:34-36`: marks spans as measured when enabled.
- **Inference**: Marks Resilience4j spans as measured for stats/metrics.

### `DD_RESILIENCE4J_TAG_METRICS_ENABLED` (A)

- **Mapping**: `DD_RESILIENCE4J_TAG_METRICS_ENABLED` ↔ `TraceInstrumentationConfig.RESILIENCE4J_TAG_METRICS_ENABLED` (`"resilience4j.tag-metrics.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2699-2700`: reads the boolean (default `false`).
  - `dd-java-agent/instrumentation/resilience4j/resilience4j-2.0/src/main/java/datadog/trace/instrumentation/resilience4j/RetryDecorator.java:27-40`: adds retry metrics as span tags when enabled.
  - `dd-java-agent/instrumentation/resilience4j/resilience4j-2.0/src/main/java/datadog/trace/instrumentation/resilience4j/CircuitBreakerDecorator.java:20-32`: adds circuit-breaker metrics as span tags when enabled.
- **Inference**: Enables tagging Resilience4j spans with Resilience4j metrics.

### `DD_RESOLVER_CACHE_CONFIG` (A)

- **Mapping**: `DD_RESOLVER_CACHE_CONFIG` ↔ `TraceInstrumentationConfig.RESOLVER_CACHE_CONFIG` (`"resolver.cache.config"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:311-313`: reads an enum value with default `ResolverCacheConfig.MEMOS`.
  - `internal-api/src/main/java/datadog/trace/api/ResolverCacheConfig.java:3-116`: defines named presets like `MEMOS`, `LARGE`, `SMALL`, etc, that control cache sizes.
- **Inference**: Picks a preset for resolver cache sizing/behavior.

### `DD_RESOLVER_CACHE_DIR` (A)

- **Mapping**: `DD_RESOLVER_CACHE_DIR` ↔ `TraceInstrumentationConfig.RESOLVER_CACHE_DIR` (`"resolver.cache.dir"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:314,573-574`: reads/exposes the directory.
- **Inference**: Provides a directory for resolver cache storage (when used).

### `DD_RESOLVER_NAMES_ARE_UNIQUE` (A)

- **Mapping**: `DD_RESOLVER_NAMES_ARE_UNIQUE` ↔ `TraceInstrumentationConfig.RESOLVER_NAMES_ARE_UNIQUE` (`"resolver.names.are.unique"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:315,589-591`: reads/exposes the boolean.
- **Inference**: Enables resolver optimizations when names are unique.

### `DD_RESOLVER_RESET_INTERVAL` (A)

- **Mapping**: `DD_RESOLVER_RESET_INTERVAL` ↔ `TraceInstrumentationConfig.RESOLVER_RESET_INTERVAL` (`"resolver.reset.interval"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:254`: default is `300` seconds.
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:321-324,605-606`: reads/exposes the interval (disabled in native-image builder mode).
- **Inference**: Controls how often resolver caches reset/cleanup.

### `DD_RESOLVER_SIMPLE_METHOD_GRAPH` (A)

- **Mapping**: `DD_RESOLVER_SIMPLE_METHOD_GRAPH` ↔ `TraceInstrumentationConfig.RESOLVER_SIMPLE_METHOD_GRAPH` (`"resolver.simple.method.graph"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:316-318,593-594`: reads/exposes the boolean; default is `true` except in native-image builder mode.
- **Inference**: Toggles use of a simpler method graph implementation during resolution/matching.


### `DD_RESOLVER_USE_LOADCLASS` (A)

- **Mapping**: `DD_RESOLVER_USE_LOADCLASS` ↔ `TraceInstrumentationConfig.RESOLVER_USE_LOADCLASS` (`"resolver.use.loadclass"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:319,597-598`: reads/exposes the boolean (default `true`).
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/bytebuddy/outline/TypeFactory.java:46-47`: sets `fallBackToLoadClass` from this config and uses it as the resolver fallback behavior.
  - `dd-java-agent/instrumentation-testing/src/test/groovy/locator/ClassInjectingLoadClassDisabledForkedTest.groovy:23,53-60`: test verifies disabling this prevents finding classes that were injected via `defineClass`.
- **Inference**: Controls whether the resolver will fall back to `loadClass` when resource-based classfile lookup fails.

### `DD_RESOLVER_USE_URL_CACHES` (A)

- **Mapping**: `DD_RESOLVER_USE_URL_CACHES` ↔ `TraceInstrumentationConfig.RESOLVER_USE_URL_CACHES` (`"resolver.use.url.caches"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:320,601-602`: reads/exposes a nullable `Boolean` (unset means “don’t override”).
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/bytebuddy/ClassFileLocators.java:118,140-146`: if this value is non-null, calls `URLConnection#setUseCaches(...)` before reading classfile bytes from the URL.
- **Inference**: Allows forcing URLConnection caching behavior for classfile resource reads.

### `DD_RUM_ENABLED` (A)

- **Mapping**: `DD_RUM_ENABLED` ↔ `RumConfig.RUM_ENABLED` (`"rum.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:267`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:250`: reads the boolean and exposes it via `instrumenterConfig.isRumEnabled()`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2998-3021`: only builds `RumInjectorConfig` when `rum.enabled` is true; otherwise returns `null`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjector.java:38-63`: injector is enabled only if `rum.enabled` is true and `RumInjectorConfig` is valid (non-null).
  - `dd-java-agent/instrumentation/servlet/javax-servlet/javax-servlet-3.0/src/main/java/datadog/trace/instrumentation/servlet3/RumHttpServletResponseWrapper.java:72-83,102-114`: wrapper injects snippet bytes/chars around `</head>` when enabled.
- **Inference**: Master toggle to allow injecting the Datadog browser RUM SDK snippet into HTML responses.

### `DD_RUM_APPLICATION_ID` (A)

- **Mapping**: `DD_RUM_APPLICATION_ID` ↔ `RumConfig.RUM_APPLICATION_ID` (`"rum.application.id"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3003-3005`: passed into `RumInjectorConfig`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:86-88`: must be non-empty, otherwise config is invalid and injection is disabled.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:171-173`: included as `applicationId` in the injected init JSON.
- **Inference**: RUM application identifier used by the injected browser SDK.

### `DD_RUM_CLIENT_TOKEN` (A)

- **Mapping**: `DD_RUM_CLIENT_TOKEN` ↔ `RumConfig.RUM_CLIENT_TOKEN` (`"rum.client.token"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3004-3006`: passed into `RumInjectorConfig`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:90-92`: must be non-empty.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:172-173`: included as `clientToken` in the injected init JSON.
- **Inference**: Client token for the injected browser SDK.

### `DD_RUM_SITE` (A)

- **Mapping**: `DD_RUM_SITE` ↔ `RumConfig.RUM_SITE` (`"rum.site"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:268`: default is `datadoghq.com`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3006-3007`: passed into `RumInjectorConfig`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:94-100,131-137`: defaults when unset and validates the value against an allowlist.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:157-165`: affects the browser SDK script URL.
- **Inference**: Selects the Datadog site/region for the injected browser SDK.

### `DD_RUM_SERVICE` (A)

- **Mapping**: `DD_RUM_SERVICE` ↔ `RumConfig.RUM_SERVICE` (`"rum.service"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3007-3008`: passed into `RumInjectorConfig`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:176-178`: included as `service` in injected init JSON when set.
- **Inference**: Optional service name passed to the injected browser SDK.

### `DD_RUM_ENVIRONMENT` (A)

- **Mapping**: `DD_RUM_ENVIRONMENT` ↔ `RumConfig.RUM_ENVIRONMENT` (`"rum.environment"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3008-3009`: passed into `RumInjectorConfig`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:179-181`: included as `env` in injected init JSON when set.
- **Inference**: Optional environment passed to the injected browser SDK.

### `DD_RUM_MAJOR_VERSION` (A)

- **Mapping**: `DD_RUM_MAJOR_VERSION` ↔ `RumConfig.RUM_MAJOR_VERSION` (`"rum.major.version"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:269`: default is `6`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3009-3010`: passed into `RumInjectorConfig`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:103-106`: only accepts `5` or `6`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:157-165`: used to select the injected SDK script URL.
- **Inference**: Chooses which major version of the browser RUM SDK is injected.

### `DD_RUM_VERSION` (A)

- **Mapping**: `DD_RUM_VERSION` ↔ `RumConfig.RUM_VERSION` (`"rum.version"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3010-3011`: passed into `RumInjectorConfig`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:182-184`: included as `version` in injected init JSON when set.
- **Inference**: Optional service version passed to the injected browser SDK.

### `DD_RUM_TRACK_USER_INTERACTION` (A)

- **Mapping**: `DD_RUM_TRACK_USER_INTERACTION` ↔ `RumConfig.RUM_TRACK_USER_INTERACTION` (`"rum.track.user.interaction"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3011-3012`: passed into `RumInjectorConfig` as a nullable `Boolean`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:185-187`: included as `trackUserInteractions` in injected init JSON when set.
- **Inference**: Optional toggle for collecting user interaction events in the injected browser SDK.

### `DD_RUM_TRACK_RESOURCES` (A)

- **Mapping**: `DD_RUM_TRACK_RESOURCES` ↔ `RumConfig.RUM_TRACK_RESOURCES` (`"rum.track.resources"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3012-3013`: passed into `RumInjectorConfig` as a nullable `Boolean`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:188-190`: included as `trackResources` in injected init JSON when set.
- **Inference**: Optional toggle for collecting resource events in the injected browser SDK.

### `DD_RUM_TRACK_LONG_TASKS` (A)

- **Mapping**: `DD_RUM_TRACK_LONG_TASKS` ↔ `RumConfig.RUM_TRACK_LONG_TASKS` (`"rum.track.long.tasks"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3013-3014`: passed into `RumInjectorConfig` as a nullable `Boolean`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:191-193`: included as `trackLongTask` in injected init JSON when set.
- **Inference**: Optional toggle for collecting long task events in the injected browser SDK.

### `DD_RUM_DEFAULT_PRIVACY_LEVEL` (A)

- **Mapping**: `DD_RUM_DEFAULT_PRIVACY_LEVEL` ↔ `RumConfig.RUM_DEFAULT_PRIVACY_LEVEL` (`"rum.default.privacy.level"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3014-3015`: reads an enum and passes it into `RumInjectorConfig`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:194-196,213-216`: included as `defaultPrivacyLevel` in injected init JSON when set; allowed values include `ALLOW`, `MASK`, `MASK_USER_INPUT`.
- **Inference**: Sets the default privacy level used by the injected browser SDK.

### `DD_RUM_SESSION_SAMPLE_RATE` (A)

- **Mapping**: `DD_RUM_SESSION_SAMPLE_RATE` ↔ `RumConfig.RUM_SESSION_SAMPLE_RATE` (`"rum.session.sample.rate"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3015-3016`: reads a nullable float and passes it into `RumInjectorConfig`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:111-114`: validates range 0–100.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:123-127`: required together with session replay sample rate if remote configuration id is not set.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:197-199`: included as `sessionSampleRate` in injected init JSON when set.
- **Inference**: Controls the percentage of sessions tracked by the injected browser SDK.

### `DD_RUM_SESSION_REPLAY_SAMPLE_RATE` (A)

- **Mapping**: `DD_RUM_SESSION_REPLAY_SAMPLE_RATE` ↔ `RumConfig.RUM_SESSION_REPLAY_SAMPLE_RATE` (`"rum.session.replay.sample.rate"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3016-3017`: reads a nullable float and passes it into `RumInjectorConfig`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:115-119`: validates range 0–100.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:123-127`: required together with session sample rate if remote configuration id is not set.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:200-202`: included as `sessionReplaySampleRate` in injected init JSON when set.
- **Inference**: Controls the percentage of tracked sessions that include Session Replay data.

### `DD_RUM_REMOTE_CONFIGURATION_ID` (A)

- **Mapping**: `DD_RUM_REMOTE_CONFIGURATION_ID` ↔ `RumConfig.RUM_REMOTE_CONFIGURATION_ID` (`"rum.remote.configuration.id"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3017`: reads/passes the string into `RumInjectorConfig`.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:123-127`: if set, relaxes the requirement to set both session sample rates.
  - `internal-api/src/main/java/datadog/trace/api/rum/RumInjectorConfig.java:203-205`: included as `remoteConfigurationId` in injected init JSON when set.
- **Inference**: Remote configuration identifier included in the injected browser SDK config.

### `DD_SERVICE_NAME` (A)

- **Mapping**: `DD_SERVICE_NAME` ↔ `GeneralConfig.SERVICE_NAME` (`"service.name"`) (used as a fallback to `DD_SERVICE`/`service`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:44`: default is `unnamed-java-app`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1381-1388`: resolves the final `serviceName` from `service`/`service.name` and computes `serviceNameSetByUser`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3075-3077`: `getServiceName()` returns the resolved value.
- **Inference**: Sets the tracer’s default service name used on spans/traces.

### `DD_SERVICE_NAME_SET_BY_USER` (A)

- **Mapping**: `DD_SERVICE_NAME_SET_BY_USER` ↔ `GeneralConfig.SERVICE_NAME_SET_BY_USER` (`"service.name.set.by.user"`).
- **Evidence**:
  - `metadata/supported-configurations.json:3540-3545`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1377-1388`: read when a service name is present (e.g. propagated from an instrumented parent process) to decide if it should be treated as user-provided.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1556-1558`: when true, removes `service` from `DD_TAGS`-derived tags to avoid overriding the service name.
- **Inference**: Marks whether the service name should be treated as user-provided for precedence rules.

### `DD_SPAN_SAMPLING_RULES` (B)

- **Mapping**: `DD_SPAN_SAMPLING_RULES` ↔ `TracerConfig.SPAN_SAMPLING_RULES` (`"span.sampling.rules"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2013-2014,3564-3569`: reads/exposes the rules string (and file path).
  - `dd-trace-core/src/main/java/datadog/trace/common/sampling/SingleSpanSampler.java:21-49`: builds a `SingleSpanSampler` from either inline JSON rules or a JSON file; warns when both inline and file are set (file ignored).
  - `dd-trace-core/src/main/java/datadog/trace/common/sampling/SpanSamplingRules.java:36-57`: JSON deserialization for a list of rules.
  - `dd-trace-core/src/main/java/datadog/trace/common/sampling/RateSamplingRule.java:120-147`: rules match spans by service + operation name, apply sample rate, and optionally enforce a max-per-second rate limit.
- **Inference**: Configures single-span sampling rules (per-span keep/drop decisions independent from trace sampling).

### `DD_SPARK_APP_NAME_AS_SERVICE` (A)

- **Mapping**: `DD_SPARK_APP_NAME_AS_SERVICE` ↔ `TraceInstrumentationConfig.SPARK_APP_NAME_AS_SERVICE` (`"spark.app-name-as-service"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:300`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2820-2821,4693-4694`: reads/exposes `useSparkAppNameAsService()`.
  - `dd-java-agent/instrumentation/spark/spark-common/src/main/java/datadog/trace/instrumentation/spark/AbstractDatadogSparkListener.java:1301-1321`: when enabled and not running on Databricks, uses `spark.app.name` as the service name unless a user-defined service name is set (except for `spark`/`hadoop`).
  - `dd-java-agent/instrumentation/spark/spark-common/src/main/java/datadog/trace/instrumentation/spark/AbstractDatadogSparkListener.java:1054-1059`: applies the computed service name to Spark spans and also sets a `service_name` tag.
- **Inference**: Makes Spark spans use the Spark application name as the Datadog service (under specific conditions).

### `DD_SPARK_TASK_HISTOGRAM_ENABLED` (A)

- **Mapping**: `DD_SPARK_TASK_HISTOGRAM_ENABLED` ↔ `TraceInstrumentationConfig.SPARK_TASK_HISTOGRAM_ENABLED` (`"spark.task-histogram.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:299`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2816-2818,4689-4691`: reads/exposes `isSparkTaskHistogramEnabled()`.
  - `dd-java-agent/instrumentation/spark/spark-common/src/main/java/datadog/trace/instrumentation/spark/SparkAggregatedTaskMetrics.java:15,116-130`: when enabled, records multiple histograms (task runtime, bytes read/written, shuffle, spilled bytes, etc.).
  - `dd-java-agent/instrumentation/spark/spark-common/src/main/java/datadog/trace/instrumentation/spark/SparkAggregatedTaskMetrics.java:190-196`: uses task runtime histogram to compute skew.
- **Inference**: Enables histogram-backed aggregation of Spark task metrics for stage spans.

### `DD_SPRING_DATA_REPOSITORY_INTERFACE_RESOURCE_NAME` (A)

- **Mapping**: `DD_SPRING_DATA_REPOSITORY_INTERFACE_RESOURCE_NAME` ↔ `TraceInstrumentationConfig.SPRING_DATA_REPOSITORY_INTERFACE_RESOURCE_NAME` (`"spring-data.repository.interface.resource-name"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1708-1710,4539-4541`: reads/exposes the boolean (default `true`).
  - `dd-java-agent/instrumentation/spring/spring-data-1.8/src/main/java/datadog/trace/instrumentation/springdata/SpringDataDecorator.java:45-49`: if enabled and repository interface is available, uses `repositoryInterface.method` as the resource name; otherwise uses method-only naming.
- **Inference**: Controls whether Spring Data spans use repository-interface-aware resource naming.

### `DD_STACK_TRACE_LENGTH_LIMIT` (A)

- **Mapping**: `DD_STACK_TRACE_LENGTH_LIMIT` ↔ `GeneralConfig.STACK_TRACE_LENGTH_LIMIT` (`"stack.trace.length.limit"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2964-2969,4761-4762`: reads/exposes `getStackTraceLengthLimit()`. Default is unlimited unless CI Visibility is enabled, in which case it defaults to 5000.
  - `dd-trace-core/src/main/java/datadog/trace/core/DDSpan.java:364-367`: uses the limit when setting `error.stack`.
  - `dd-trace-core/src/main/java/datadog/trace/core/util/StackTraces.java:16-52`: truncates/abbreviates stack traces to enforce the maximum length.
- **Inference**: Caps the amount of stack-trace text attached to spans.

### `DD_STATSD_CLIENT_QUEUE_SIZE` (A)

- **Mapping**: `DD_STATSD_CLIENT_QUEUE_SIZE` ↔ `GeneralConfig.STATSD_CLIENT_QUEUE_SIZE` (`"statsd.client.queue.size"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1878,3421-3423`: reads/exposes the (nullable) integer.
  - `products/metrics/metrics-lib/src/main/java/datadog/metrics/impl/statsd/DDAgentStatsDConnection.java:106-127`: if set, configures the StatsD client builder queue size.
- **Inference**: Overrides buffering queue size for the internal DogStatsD client.

### `DD_STATSD_CLIENT_SOCKET_BUFFER` (A)

- **Mapping**: `DD_STATSD_CLIENT_SOCKET_BUFFER` ↔ `GeneralConfig.STATSD_CLIENT_SOCKET_BUFFER` (`"statsd.client.socket.buffer"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1879,3425-3427`: reads/exposes the (nullable) integer.
  - `products/metrics/metrics-lib/src/main/java/datadog/metrics/impl/statsd/DDAgentStatsDConnection.java:129-145`: when using UDS, if set, configures socket buffer size and also caps packet size to not exceed it.
- **Inference**: Controls socket buffer sizing (UDS path) for the internal DogStatsD client.

### `DD_STATSD_CLIENT_SOCKET_TIMEOUT` (A)

- **Mapping**: `DD_STATSD_CLIENT_SOCKET_TIMEOUT` ↔ `GeneralConfig.STATSD_CLIENT_SOCKET_TIMEOUT` (`"statsd.client.socket.timeout"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1880,3429-3431`: reads/exposes the (nullable) integer.
  - `products/metrics/metrics-lib/src/main/java/datadog/metrics/impl/statsd/DDAgentStatsDConnection.java:131-135`: when using UDS, if set, configures socket timeout.
- **Inference**: Controls socket timeout (UDS path) for the internal DogStatsD client.

### `DD_SYMBOL_DATABASE_UPLOAD_ENABLED` (A)

- **Mapping**: `DD_SYMBOL_DATABASE_UPLOAD_ENABLED` ↔ `DebuggerConfig.SYMBOL_DATABASE_ENABLED` (`"symbol.database.upload.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:217`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2586-2588,4336-4338`: reads/exposes `isSymbolDatabaseEnabled()`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerAgent.java:104-106`: starts the symbol database pipeline when enabled.
- **Inference**: Master toggle for symbol database extraction/upload used by Live Debugging features.

### `DD_SYMBOL_DATABASE_FLUSH_THRESHOLD` (A)

- **Mapping**: `DD_SYMBOL_DATABASE_FLUSH_THRESHOLD` ↔ `DebuggerConfig.SYMBOL_DATABASE_FLUSH_THRESHOLD` (`"symbol.database.flush.threshold"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:219`: default is `100` classes.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2591-2593,4344-4346`: reads/exposes `getSymbolDatabaseFlushThreshold()`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerAgent.java:230-236`: passes the threshold to `SymbolAggregator(...)`.
- **Inference**: Controls batching for symbol uploads.

### `DD_SYMBOL_DATABASE_COMPRESSED` (A)

- **Mapping**: `DD_SYMBOL_DATABASE_COMPRESSED` ↔ `DebuggerConfig.SYMBOL_DATABASE_COMPRESSED` (`"symbol.database.compressed"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:220`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2594-2595,4348-4350`: reads/exposes `isSymbolDatabaseCompressed()`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/sink/SymbolSink.java:71,116-118`: if enabled, wraps payload output stream in `GZIPOutputStream`.
- **Inference**: Controls gzip compression for symbol database uploads.

### `DD_TAG_NAME_UTF8_CACHE_SIZE` (A)

- **Mapping**: `DD_TAG_NAME_UTF8_CACHE_SIZE` ↔ `GeneralConfig.TAG_NAME_UTF8_CACHE_SIZE` (`"tag.name.utf8.cache.size"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2959-2960,4753-4755`: reads/exposes cache size (default 128; clamped at >=0).
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/ddagent/TraceMapperV0_4.java:27-35`: creates a `SimpleUtf8Cache` for tag-name encoding when size > 0.
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/ddagent/SimpleUtf8Cache.java:6-16`: cache reduces allocation overhead for UTF-8 encodings.
- **Inference**: Tunes tag-name UTF-8 caching for trace serialization.

### `DD_TAG_VALUE_UTF8_CACHE_SIZE` (A)

- **Mapping**: `DD_TAG_VALUE_UTF8_CACHE_SIZE` ↔ `GeneralConfig.TAG_VALUE_UTF8_CACHE_SIZE` (`"tag.value.utf8.cache.size"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2961-2962,4757-4759`: reads/exposes cache size (default 384; clamped at >=0).
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/ddagent/TraceMapperV0_4.java:32-35`: creates a `GenerationalUtf8Cache` for tag-value encoding when size > 0.
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/ddagent/GenerationalUtf8Cache.java:7-18`: two-level generational cache intended for tag values.
- **Inference**: Tunes tag-value UTF-8 caching for trace serialization.

### `DD_TELEMETRY_DEBUG_REQUESTS_ENABLED` (A)

- **Mapping**: `DD_TELEMETRY_DEBUG_REQUESTS_ENABLED` ↔ `GeneralConfig.TELEMETRY_DEBUG_REQUESTS_ENABLED` (`"telemetry.debug.requests.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:302`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2876-2878,5385-5387`: reads/exposes `isTelemetryDebugRequestsEnabled()`.
  - `telemetry/src/main/java/datadog/telemetry/TelemetrySystem.java:99-115`: passes the flag into `TelemetryService.build(..., debug)`.
- **Inference**: Enables debug mode for telemetry requests.

### `DD_TELEMETRY_DEPENDENCY_COLLECTION_ENABLED` (A)

- **Mapping**: `DD_TELEMETRY_DEPENDENCY_COLLECTION_ENABLED` ↔ `GeneralConfig.TELEMETRY_DEPENDENCY_COLLECTION_ENABLED` (`"telemetry.dependency-collection.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:261`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2179-2182,3739-3741`: reads/exposes `isTelemetryDependencyServiceEnabled()`.
  - `telemetry/src/main/java/datadog/telemetry/TelemetrySystem.java:40-48`: creates/installs/schedules the dependency service only if enabled.
- **Inference**: Toggles dependency collection via classloading telemetry.

### `DD_TELEMETRY_DEPENDENCY_RESOLUTION_PERIOD_MILLIS` (A)

- **Mapping**: `DD_TELEMETRY_DEPENDENCY_RESOLUTION_PERIOD_MILLIS` ↔ `GeneralConfig.TELEMETRY_DEPENDENCY_RESOLUTION_PERIOD_MILLIS` (`"telemetry.dependency.resolution.period.millis"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2928-2931,5284-5286`: reads/exposes period; default is `1000` ms.
  - `telemetry/src/main/java/datadog/telemetry/dependency/DependencyService.java:32-40`: schedules periodic resolution at this period.
- **Inference**: Controls how frequently dependency resolution work runs.

### `DD_TELEMETRY_DEPENDENCY_RESOLUTION_QUEUE_SIZE` (A)

- **Mapping**: `DD_TELEMETRY_DEPENDENCY_RESOLUTION_QUEUE_SIZE` ↔ `GeneralConfig.TELEMETRY_DEPENDENCY_RESOLUTION_QUEUE_SIZE` (`"telemetry.dependency-resolution.queue.size"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:263`: default is `100000`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2183-2186,3751-3753`: reads/exposes `getTelemetryDependencyResolutionQueueSize()`.
  - `telemetry/src/main/java/datadog/telemetry/dependency/DependencyResolverQueue.java:20-56`: enforces the max size; once reached it disables further queuing and drops additional dependencies.
- **Inference**: Caps queued dependency locations for telemetry dependency collection.

### `DD_TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL` (A)

- **Mapping**: `DD_TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL` ↔ `GeneralConfig.TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL` (`"telemetry.extended.heartbeat.interval"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:258-260`: default is 24 hours (in seconds).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2158-2160,3731-3733`: reads/exposes `getTelemetryExtendedHeartbeatInterval()` (seconds).
  - `telemetry/src/main/java/datadog/telemetry/TelemetryRunnable.java:42-49`: used as the scheduler’s extended heartbeat period.
- **Inference**: Controls the long-period heartbeat cadence in telemetry scheduling.

### `DD_TELEMETRY_FORWARDER_MAX_TAGS` (A)

- **Mapping**: `DD_TELEMETRY_FORWARDER_MAX_TAGS` ↔ bootstrap env var read directly by `BootstrapInitializationTelemetry`.
- **Evidence**:
  - `dd-java-agent/src/main/java/datadog/trace/bootstrap/BootstrapInitializationTelemetry.java:149-161`: parses the env var and caps the number of tags forwarded for error causes; default is `5`.
  - `dd-java-agent/src/main/java/datadog/trace/bootstrap/BootstrapInitializationTelemetry.java:133-138`: truncates the list of causes to `maxTags`.
- **Inference**: Caps tag cardinality when forwarding bootstrap initialization telemetry.

### `DD_TELEMETRY_FORWARDER_PATH` (A)

- **Mapping**: `DD_TELEMETRY_FORWARDER_PATH` ↔ bootstrap env var read directly by `AgentBootstrap` / `AgentPreCheck`.
- **Evidence**:
  - `dd-java-agent/src/main/java/datadog/trace/bootstrap/AgentBootstrap.java:93-101`: if set, enables JSON-based bootstrap initialization telemetry forwarding using the provided executable path; if unset, uses a no-op telemetry instance.
  - `dd-java-agent/src/main/java6/datadog/trace/bootstrap/AgentPreCheck.java:107-110`: also reads this to forward telemetry when Java version is incompatible.
- **Inference**: Enables bootstrap initialization telemetry forwarding through an external forwarder executable.

### `DD_TELEMETRY_METRICS_ENABLED` (A)

- **Mapping**: `DD_TELEMETRY_METRICS_ENABLED` ↔ `GeneralConfig.TELEMETRY_METRICS_ENABLED` (`"telemetry.metrics.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2172,3743-3745`: reads/exposes `isTelemetryMetricsEnabled()` (default `true`).
  - `telemetry/src/main/java/datadog/telemetry/TelemetrySystem.java:56-71,117-120`: gates which periodic actions are installed (core metrics, integrations, WAF/IAST/CIVISIBILITY/LLMObs metrics, etc.).
- **Inference**: Master toggle for telemetry metrics collection/dispatch.

### `DD_TELEMETRY_METRICS_INTERVAL` (A)

- **Mapping**: `DD_TELEMETRY_METRICS_INTERVAL` ↔ `GeneralConfig.TELEMETRY_METRICS_INTERVAL` (`"telemetry.metrics.interval"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:260`: default is `10` seconds.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2162-2169,3735-3737`: reads/exposes `getTelemetryMetricsInterval()` and validates the value is in range 0.1–3600.
  - `telemetry/src/main/java/datadog/telemetry/TelemetryRunnable.java:42-49`: scheduler uses `getTelemetryMetricsInterval() * 1000` to drive the metrics cadence.
- **Inference**: Controls how often telemetry metrics are collected/sent.

### `DD_TEST_FAILED_TEST_REPLAY_ENABLED` (A)

- **Mapping**: `DD_TEST_FAILED_TEST_REPLAY_ENABLED` ↔ `CiVisibilityConfig.TEST_FAILED_TEST_REPLAY_ENABLED` (`"test.failed.test.replay.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2484-2485,4228-4229`: reads/exposes `isCiVisibilityFailedTestReplayEnabled()` (default `true`).
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/config/ExecutionSettingsFactoryImpl.java:187-191`: final enablement depends on backend setting *and* the local kill-switch.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/CiVisibilitySystem.java:107-109`: when enabled in execution settings, updates debugger config to enable exception replay (`DebuggerConfigBridge.updateConfig(...)`).
- **Inference**: Toggles Failed Test Replay (and the related exception replay/debugger enablement during tests).

### `DD_TEST_MANAGEMENT_ENABLED` (A)

- **Mapping**: `DD_TEST_MANAGEMENT_ENABLED` ↔ `CiVisibilityConfig.TEST_MANAGEMENT_ENABLED` (`"test.management.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2476,4220-4222`: reads/exposes `isCiVisibilityTestManagementEnabled()` (default `true`).
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/config/ExecutionSettingsFactoryImpl.java:323-332`: acts as a kill-switch; backend setting must also be enabled or Test Management is disabled.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/test/ExecutionStrategy.java:67-88`: quarantine/disabled/attempt-to-fix logic is gated on `TestManagementSettings.isEnabled()`.
- **Inference**: Master toggle for CI Visibility Test Management features.

### `DD_TEST_MANAGEMENT_ATTEMPT_TO_FIX_RETRIES` (A)

- **Mapping**: `DD_TEST_MANAGEMENT_ATTEMPT_TO_FIX_RETRIES` ↔ `CiVisibilityConfig.TEST_MANAGEMENT_ATTEMPT_TO_FIX_RETRIES` (`"test.management.attempt.to.fix.retries"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2477-2478,4224-4226`: reads/exposes override value (nullable).
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/config/ExecutionSettingsFactoryImpl.java:334-339`: if set, overrides backend `attempt_to_fix_retries` setting.
  - `dd-java-agent/agent-ci-visibility/src/main/java/datadog/trace/civisibility/config/TestManagementSettings.java:85-91`: backend default is `20` when missing.
- **Inference**: Overrides how many times “attempt to fix” tests are retried/executed.

### `DD_THIRD_PARTY_INCLUDES` (A)

- **Mapping**: `DD_THIRD_PARTY_INCLUDES` ↔ `DebuggerConfig.THIRD_PARTY_INCLUDES` (`"third.party.includes"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2629-2632,4392-4394`: reads/exposes `getThirdPartyIncludes()` (as a set of prefixes).
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/ThirdPartyLibraries.java:44-52`: merges configured prefixes with defaults from `/third_party_libraries.json` to build the “third-party libraries” prefix set used for filtering.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/util/ClassNameFiltering.java:19-49`: uses the prefix set as the exclusion trie for class filtering.
- **Inference**: Lets users add extra package prefixes to treat as “third-party libraries” for debugger-related class filtering.

### `DD_THIRD_PARTY_DETECTION_INCLUDES` (A)

- **Mapping**: `DD_THIRD_PARTY_DETECTION_INCLUDES` ↔ `DebuggerConfig.THIRD_PARTY_DETECTION_INCLUDES` (`"third.party.detection.includes"`) and is treated as an alias for `third.party.includes`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2629-2632`: reads `THIRD_PARTY_INCLUDES` with alias `THIRD_PARTY_DETECTION_INCLUDES`.
- **Inference**: Alias of `DD_THIRD_PARTY_INCLUDES`.

### `DD_THIRD_PARTY_EXCLUDES` (A)

- **Mapping**: `DD_THIRD_PARTY_EXCLUDES` ↔ `DebuggerConfig.THIRD_PARTY_EXCLUDES` (`"third.party.excludes"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2633-2636,4396-4398`: reads/exposes `getThirdPartyExcludes()` (as a set of prefixes).
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/ThirdPartyLibraries.java:59-62`: returns the configured exclude set.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/util/ClassNameFiltering.java:44-49`: include trie overrides exclusion: if a class matches the include trie, it will not be excluded even if it matches the exclude trie.
- **Inference**: Allowlist package prefixes to treat as “first-party” (not excluded) when applying third-party library filtering.

### `DD_THIRD_PARTY_DETECTION_EXCLUDES` (A)

- **Mapping**: `DD_THIRD_PARTY_DETECTION_EXCLUDES` ↔ `DebuggerConfig.THIRD_PARTY_DETECTION_EXCLUDES` (`"third.party.detection.excludes"`) and is treated as an alias for `third.party.excludes`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2633-2636`: reads `THIRD_PARTY_EXCLUDES` with alias `THIRD_PARTY_DETECTION_EXCLUDES`.
- **Inference**: Alias of `DD_THIRD_PARTY_EXCLUDES`.

### `DD_THIRD_PARTY_SHADING_IDENTIFIERS` (A)

- **Mapping**: `DD_THIRD_PARTY_SHADING_IDENTIFIERS` ↔ `DebuggerConfig.THIRD_PARTY_SHADING_IDENTIFIERS` (`"third.party.shading.identifiers"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2637-2638,4400-4402`: reads/exposes `getThirdPartyShadingIdentifiers()`.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/ThirdPartyLibraries.java:65-69`: merges configured shading identifiers with a built-in default list.
  - `dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/util/ClassNameFiltering.java:56-66`: uses shading identifiers to find a shaded prefix and skip it when applying include/exclude matching.
- **Inference**: Helps third-party detection work when dependencies are shaded/relocated under known package segments.

### `DD_TRACE_AGENT_PATH` (A)

- **Mapping**: `DD_TRACE_AGENT_PATH` ↔ `TracerConfig.TRACE_AGENT_PATH` (`"trace.agent.path"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2757,4613-4615`: reads/exposes `getTraceAgentPath()`.
  - `communication/src/main/java/datadog/communication/ddagent/ExternalAgentLauncher.java:26-38`: on Azure App Services, if set, builds a `ProcessBuilder` and starts an external `trace-agent` process; if unset, logs a warning and does not start it.
- **Inference**: Used only in Azure App Services mode to launch an external trace-agent helper.

### `DD_TRACE_AGENT_ARGS` (A)

- **Mapping**: `DD_TRACE_AGENT_ARGS` ↔ `TracerConfig.TRACE_AGENT_ARGS` (`"trace.agent.args"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2758-2765,4617-4619`: reads/parses the args string into a list.
  - `communication/src/main/java/datadog/communication/ddagent/ExternalAgentLauncher.java:28-35`: appends the args to the external `trace-agent` command line.
- **Inference**: Provides extra CLI args for the Azure App Services external trace-agent helper.

### `DD_TRACE_AGENT_V0_5_ENABLED` (A)

- **Mapping**: `DD_TRACE_AGENT_V0_5_ENABLED` ↔ `TracerConfig.ENABLE_TRACE_AGENT_V05` (`"trace.agent.v0.5.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:92`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1988-1989,4551-4552`: reads/exposes `isTraceAgentV05Enabled()`.
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/DDAgentWriter.java:40-47,137-151`: passes the flag into `DDAgentFeaturesDiscovery`.
  - `communication/src/main/java/datadog/communication/ddagent/DDAgentFeaturesDiscovery.java:114-117`: when enabled, trace endpoints include `v0.5/traces` (preferred), otherwise only `v0.4/v0.3`.
- **Inference**: Enables using Datadog Agent trace intake endpoint v0.5 when available.

### `DD_TRACE_AKKA_FORK_JOIN_TASK_NAME` (A)

- **Mapping**: `DD_TRACE_AKKA_FORK_JOIN_TASK_NAME` ↔ `TraceInstrumentationConfig.AKKA_FORK_JOIN_TASK_NAME` (`"trace.akka.fork.join.task.name"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:295,505-507`: reads/exposes the configured class name (default empty string).
  - `dd-java-agent/instrumentation/akka/akka-actor-2.5/src/main/java/datadog/trace/instrumentation/akka/concurrent/AkkaForkJoinTaskInstrumentation.java:72-75`: if set, the instrumentation also matches subclasses of the configured class name.
- **Inference**: Allows configuring a shaded/custom Akka fork-join task class for context propagation instrumentation.

### `DD_TRACE_AKKA_FORK_JOIN_EXECUTOR_TASK_NAME` (A)

- **Mapping**: `DD_TRACE_AKKA_FORK_JOIN_EXECUTOR_TASK_NAME` ↔ `TraceInstrumentationConfig.AKKA_FORK_JOIN_EXECUTOR_TASK_NAME` (`"trace.akka.fork.join.executor.task.name"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:296,509-511`: reads/exposes the configured class name (default empty string).
  - `dd-java-agent/instrumentation/akka/akka-actor-2.5/src/main/java/datadog/trace/instrumentation/akka/concurrent/AkkaForkJoinExecutorTaskInstrumentation.java:47-50`: uses it as `configuredMatchingType()` for `ForConfiguredType`.
- **Inference**: Allows configuring a shaded/custom Akka fork-join executor task wrapper for instrumentation.

### `DD_TRACE_AKKA_FORK_JOIN_POOL_NAME` (A)

- **Mapping**: `DD_TRACE_AKKA_FORK_JOIN_POOL_NAME` ↔ `TraceInstrumentationConfig.AKKA_FORK_JOIN_POOL_NAME` (`"trace.akka.fork.join.pool.name"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:297,513-515`: reads/exposes the configured class name (default empty string).
  - `dd-java-agent/instrumentation/akka/akka-actor-2.5/src/main/java/datadog/trace/instrumentation/akka/concurrent/AkkaForkJoinPoolInstrumentation.java:37-39`: uses it as `configuredMatchingType()` for `ForConfiguredType`.
- **Inference**: Allows configuring a shaded/custom Akka fork-join pool class for context propagation instrumentation.

### `DD_TRACE_AMQP_E2E_DURATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_AMQP_E2E_DURATION_ENABLED` ↔ `Config.isEndToEndDurationEnabled(..., "amqp", ...)` (suffix `".e2e.duration.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5218-5222`: `isEndToEndDurationEnabled` checks `{trace.<integration>.e2e.duration.enabled, <integration>.e2e.duration.enabled}`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/MessagingClientDecorator.java:10-16,23-27`: when enabled for an instrumentation, calls `span.beginEndToEnd()` on start.
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitDecorator.java:265-271`: when enabled, finishes consumer span via `finishWithEndToEnd()`.
  - `dd-trace-core/src/main/java/datadog/trace/core/DDSpan.java:205-242`: `finishWithEndToEnd()` sets tag `record.e2e_duration_ms` when end-to-end start time is available.
- **Inference**: Enables end-to-end duration recording on AMQP messaging spans.

### `DD_TRACE_ANALYTICS_ENABLED` (B)

- **Mapping**: `DD_TRACE_ANALYTICS_ENABLED` ↔ `TracerConfig.TRACE_ANALYTICS_ENABLED` (`"trace.analytics.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:236`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1991-1992,3526-3527`: reads/exposes `isTraceAnalyticsEnabled()`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpServerDecorator.java:124-127`: uses it as the default for HTTP server integration analytics.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/BaseDecorator.java:49-55,74-76`: when analytics is enabled for an integration, sets `analytics.sample_rate` metric on spans.
- **Inference**: Global default toggle for trace analytics sample-rate tagging.

### `DD_TRACE_ANNOTATIONS_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_ANNOTATIONS_LEGACY_TRACING_ENABLED` ↔ `InstrumenterConfig.isLegacyInstrumentationEnabled(true, "trace.annotations")` (suffix `".legacy.tracing.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:659-663`: legacy instrumentation enablement uses `*.legacy.tracing.enabled`.
  - `dd-java-agent/instrumentation/datadog/tracing/trace-annotation/src/main/java/datadog/trace/instrumentation/trace_annotation/TraceDecorator.java:16-18,73-79`: when legacy is enabled and annotation does not set an explicit operation name, uses default operation name `trace.annotation`; otherwise uses method-based operation name.
- **Inference**: Controls old vs improved naming for `@Trace` spans.

### `DD_TRACE_ANNOTATION_ASYNC` (A)

- **Mapping**: `DD_TRACE_ANNOTATION_ASYNC` ↔ `TraceInstrumentationConfig.TRACE_ANNOTATION_ASYNC` (`"trace.annotation.async"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:232`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:339-340,638-639`: reads/exposes `isTraceAnnotationAsync()`.
  - `dd-java-agent/instrumentation/datadog/tracing/trace-annotation/src/main/java/datadog/trace/instrumentation/trace_annotation/TraceDecorator.java:19,101-108`: when enabled, wraps async results and finishes span on completion; otherwise finishes immediately on method return.
- **Inference**: Enables async completion support for `@Trace` spans.

### `DD_TRACE_ARMERIA_GRPC_MESSAGE_ENABLED` (A)

- **Mapping**: `DD_TRACE_ARMERIA_GRPC_MESSAGE_ENABLED` ↔ integration enablement for `armeria-grpc-message` (`trace.armeria-grpc-message.enabled` / `integration.armeria-grpc-message.enabled` etc).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:391-408`: integration enablement precedence for `trace.<name>.enabled`, `trace.integration.<name>.enabled`, `integration.<name>.enabled`.
  - `dd-java-agent/instrumentation/armeria/armeria-grpc-0.84/src/main/java/datadog/trace/instrumentation/armeria/grpc/client/ClientCallImplInstrumentation.java:59-63`: when enabled, instruments `onNext`/`messageRead`.
  - `dd-java-agent/instrumentation/armeria/armeria-grpc-0.84/src/main/java/datadog/trace/instrumentation/armeria/grpc/client/ClientCallImplInstrumentation.java:196-214`: creates a `grpc.message` span for each received message.
- **Inference**: Toggles message-level `grpc.message` spans for Armeria gRPC client streaming messages.

### `DD_TRACE_AWSADD_SPAN_POINTERS` (A)

- **Mapping**: `DD_TRACE_AWSADD_SPAN_POINTERS` ↔ `Config.isAddSpanPointers("aws")` which checks `{trace.awsadd.span.pointers, awsadd.span.pointers}` (note: no dot between `aws` and `add`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5274-5275`: `isAddSpanPointers("aws")` delegates to `ConfigProvider.isEnabled(..., "add.span.pointers", true)`.
  - `dd-trace-core/src/main/java/datadog/trace/core/tagprocessor/TagsPostProcessorFactory.java:41-43`: when enabled, adds `SpanPointersProcessor` to the post-processing chain.
  - `dd-trace-core/src/main/java/datadog/trace/core/tagprocessor/SpanPointersProcessor.java:39-52,153-163`: computes span-pointer hashes for S3/DynamoDB and adds span links with `ptr.kind/ptr.dir/ptr.hash` attributes.
  - `dd-java-agent/instrumentation/aws-java/aws-java-s3-2.0/src/main/java/datadog/trace/instrumentation/aws/v2/s3/S3Interceptor.java:26-59` and `dd-java-agent/instrumentation/aws-java/aws-java-dynamodb-2.0/src/main/java/datadog/trace/instrumentation/aws/v2/dynamodb/DynamoDbInterceptor.java:28-50`: only exports the tags needed to build span pointers when enabled.
- **Inference**: Enables adding span-pointer span links for supported AWS operations (currently S3 object and DynamoDB item).

### `DD_TRACE_AWS_PROPAGATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_AWS_PROPAGATION_ENABLED` ↔ `Config.isAwsPropagationEnabled()` (computed via `isPropagationEnabled(true, "aws", "aws-sdk")`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2640-2642,4451-4453`: reads and exposes `isAwsPropagationEnabled()`.
  - `dd-trace-core/src/main/java/datadog/trace/core/propagation/DatadogHttpCodec.java:105-110,138-140`: when enabled, accepts `X-Amzn-Trace-Id` and parses it as X-Ray context.
  - `dd-java-agent/instrumentation/aws-java/aws-java-sdk-2.2/src/main/java/datadog/trace/instrumentation/aws/v2/TracingExecutionInterceptor.java:80-91` and `dd-java-agent/instrumentation/aws-java/aws-java-sdk-1.11/src/main/java/datadog/trace/instrumentation/aws/v0/TracingRequestHandler.java:74-80`: when enabled, injects X-Ray propagation on outgoing AWS SDK requests.
- **Inference**: Master toggle for AWS X-Ray header (`X-Amzn-Trace-Id`) propagation support (extract + inject).

### `DD_TRACE_AWS_SDK_PROPAGATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_AWS_SDK_PROPAGATION_ENABLED` ↔ `trace.aws-sdk.propagation.enabled` (participates in the same composite `awsPropagationEnabled = isPropagationEnabled(true, "aws", "aws-sdk")`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2640-2642`: propagation enablement checks both `"aws"` and `"aws-sdk"` keys.
  - `dd-java-agent/instrumentation/aws-java/aws-java-sdk-2.2/src/main/java/datadog/trace/instrumentation/aws/v2/TracingExecutionInterceptor.java:80-91`: outgoing AWS SDK injection is gated by `Config.get().isAwsPropagationEnabled()`.
- **Inference**: AWS SDK-scoped toggle for AWS X-Ray propagation; disabling either this or `DD_TRACE_AWS_PROPAGATION_ENABLED` disables AWS propagation overall.

### `DD_TRACE_AWS_SDK_E2E_DURATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_AWS_SDK_E2E_DURATION_ENABLED` ↔ `trace.aws-sdk.e2e.duration.enabled` (via `Config.isEndToEndDurationEnabled(..., "aws-sdk")`).
- **Evidence**:
  - `dd-java-agent/instrumentation/aws-java/aws-java-sqs-2.0/src/main/java/datadog/trace/instrumentation/aws/v2/sqs/SqsDecorator.java:59-62` (and v1 equivalent): SQS decorator declares `instrumentationNames() == ["aws-sdk"]` and extends `MessagingClientDecorator`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/MessagingClientDecorator.java:10-16,23-27`: when enabled for an instrumentation, calls `span.beginEndToEnd()` on span start.
  - `dd-java-agent/instrumentation/aws-java/aws-java-sqs-2.0/src/main/java/datadog/trace/instrumentation/aws/v2/sqs/TracingIterator.java:44-49,60-99`: uses `activateNext(span)` and closes iterations with `closePrevious(true)`.
  - `dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScopeManager.java:236-251`: `closePrevious(true)` finishes iteration spans via `finishWithEndToEnd()`, which sets `record.e2e_duration_ms` if an end-to-end start time exists.
- **Inference**: Enables end-to-end duration tagging on AWS SDK (SQS) message-processing spans.

### `DD_TRACE_AWS_SDK_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_AWS_SDK_LEGACY_TRACING_ENABLED` ↔ `Config.isAwsLegacyTracingEnabled()` (via `trace.aws-sdk.legacy.tracing.enabled`, gated on inferred-services support).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5247-5250`: legacy AWS tracing is enabled only when inferred services are allowed and the `aws-sdk` legacy toggle is enabled.
  - `dd-java-agent/instrumentation/netty/netty-4.1/src/main/java/datadog/trace/instrumentation/netty41/client/HttpClientRequestTracingHandler.java:46-74`: when legacy tracing is **disabled** and an AWS SDK call is detected, Netty HTTP client instrumentation avoids creating an extra HTTP client span beneath the AWS SDK call.
  - `dd-java-agent/instrumentation/aws-java/aws-java-sdk-2.2/src/main/java/datadog/trace/instrumentation/aws/v2/TracingExecutionInterceptor.java:96-104`: chooses between activating a real span vs a blackhole span based on `AWS_LEGACY_TRACING`.
- **Inference**: Toggles the legacy AWS SDK tracing mode, affecting span structure (and whether extra underlying HTTP client spans are created) and inferred-service behavior.

### `DD_TRACE_AXIS2_TRANSPORT_ENABLED` (A) — **unknown**

- **Evidence**:
  - Present in `metadata/supported-configurations.json` (`DD_TRACE_AXIS2_TRANSPORT_ENABLED`, default `true`).
  - No code references found for the expected property forms (e.g., `trace.axis2.transport.enabled` / `trace.axis2_transport.enabled`) and it is not read into `Config` / `InstrumenterConfig`, nor used as an `InstrumenterModule` name.
- **Outcome**: Added to `workspace/result/unknown_configurations.json` because its runtime effect could not be determined from code.

### `DD_TRACE_AXIS_PROMOTE_RESOURCE_NAME` (A)

- **Mapping**: `DD_TRACE_AXIS_PROMOTE_RESOURCE_NAME` ↔ `TraceInstrumentationConfig.AXIS_PROMOTE_RESOURCE_NAME` (`"trace.axis.promote.resource-name"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2827,4701-4703`: reads/exposes `isAxisPromoteResourceName()`.
  - `dd-java-agent/instrumentation/axis2-1.3/src/main/java/datadog/trace/instrumentation/axis2/AxisMessageDecorator.java:64-75`: when enabled for server-side messages, promotes the SOAP action/address to the local root span resource name.
- **Inference**: Uses Axis2 SOAP action/address as the resource name for the root server span.

### `DD_TRACE_AXIS_TRANSPORT_CLASS_NAME` (A)

- **Mapping**: `DD_TRACE_AXIS_TRANSPORT_CLASS_NAME` ↔ `TraceInstrumentationConfig.AXIS_TRANSPORT_CLASS_NAME` (`"trace.axis.transport.class.name"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:293,501-503`: reads/exposes `getAxisTransportClassName()` (default `""`).
  - `dd-java-agent/instrumentation/axis2-1.3/src/main/java/datadog/trace/instrumentation/axis2/AxisTransportInstrumentation.java:33-37`: uses it as `configuredMatchingType()` so custom transport sender classes can be instrumented.
- **Inference**: Lets users specify a custom Axis2 transport sender class to instrument for `axis2.transport` spans.

### `DD_TRACE_CLASSES_EXCLUDE_FILE` (A)

- **Mapping**: `DD_TRACE_CLASSES_EXCLUDE_FILE` ↔ `TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE_FILE` (`"trace.classes.exclude.file"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:304,525-526`: reads/exposes `getExcludedClassesFile()`.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/bytebuddy/matcher/CustomExcludes.java:27-35`: reads the file and loads entries into a class-name trie.
  - `dd-java-agent/agent-builder/src/main/java/datadog/trace/agent/tooling/bytebuddy/matcher/GlobalIgnoresMatcher.java:34-39`: `CustomExcludes.isExcluded(name)` participates in global ignore checks.
- **Inference**: Allows excluding additional classes/packages from instrumentation via a file.

### `DD_TRACE_CLASSLOADERS_EXCLUDE` (A)

- **Mapping**: `DD_TRACE_CLASSLOADERS_EXCLUDE` ↔ `TraceInstrumentationConfig.TRACE_CLASSLOADERS_EXCLUDE` (`"trace.classloaders.exclude"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:305,529-531`: reads/exposes excluded class-loader class names.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/bytebuddy/matcher/ClassLoaderMatchers.java:28-62`: skips classloaders whose class name is in this set.
- **Inference**: Prevents instrumentation from running in specified classloaders.

### `DD_TRACE_CLASSLOADERS_DEFER` (A)

- **Mapping**: `DD_TRACE_CLASSLOADERS_DEFER` ↔ `TraceInstrumentationConfig.TRACE_CLASSLOADERS_DEFER` (`"trace.classloaders.defer"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:307,537-538`: reads/exposes deferred class-loader class names.
  - `dd-java-agent/agent-builder/src/main/java/datadog/trace/agent/tooling/CombiningMatcher.java:29-35,69-72,161-164`: when integration deferral is active, matching is skipped for deferred classloaders until later retransformation.
- **Inference**: Selects classloaders whose classes should be matched/instrumented later when deferral is enabled.

### `DD_TRACE_CODESOURCES_EXCLUDE` (A)

- **Mapping**: `DD_TRACE_CODESOURCES_EXCLUDE` ↔ `TraceInstrumentationConfig.TRACE_CODESOURCES_EXCLUDE` (`"trace.codesources.exclude"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:306,533-535`: reads/exposes excluded code-source substrings.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/bytebuddy/matcher/CodeSourceExcludes.java:31-50`: excludes any class whose code source location path contains a configured substring.
  - `dd-java-agent/agent-builder/src/main/java/datadog/trace/agent/tooling/bytebuddy/matcher/GlobalIgnoresMatcher.java:34-39`: code-source excludes participate in global ignore checks.
- **Inference**: Excludes instrumentation for classes loaded from specific JARs/directories (by path substring match).

### `DD_TRACE_CLIENT_IP_RESOLVER_ENABLED` (A)

- **Mapping**: `DD_TRACE_CLIENT_IP_RESOLVER_ENABLED` ↔ `TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED` (`"trace.client-ip.resolver.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2003-2004`: reads the toggle (default `true`).
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpServerDecorator.java:213-238,297-316`: when enabled (and AppSec/client-ip logic allows), resolves client IP from forwarding headers and sets `http.client_ip` and forwarding tags.
- **Inference**: Controls whether the tracer resolves/tags the client IP address on HTTP server spans.

### `DD_TRACE_CLOCK_SYNC_PERIOD` (A)

- **Mapping**: `DD_TRACE_CLOCK_SYNC_PERIOD` ↔ `TracerConfig.CLOCK_SYNC_PERIOD` (`"trace.clock.sync.period"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:96`: default is `30` seconds.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1856,3409-3411`: reads/exposes `getClockSyncPeriod()`.
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:647-648,978-987`: converts to nanoseconds and periodically re-syncs drift in `getTimeWithNanoTicks`.
- **Inference**: Controls how often the tracer syncs its monotonic clock conversion against wall-clock time.

### `DD_TRACE_COUCHBASE_INTERNAL_SPANS_ENABLED` (A)

- **Mapping**: `DD_TRACE_COUCHBASE_INTERNAL_SPANS_ENABLED` ↔ `TraceInstrumentationConfig.COUCHBASE_INTERNAL_SPANS_ENABLED` (`"trace.couchbase.internal-spans.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:294`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1414-1416,4673-4674`: reads/exposes `isCouchbaseInternalSpansEnabled()`.
  - `dd-java-agent/instrumentation/couchbase/couchbase-3.2/src/main/java/datadog/trace/instrumentation/couchbase_32/client/DatadogRequestTracer.java:46-55`: when disabled, wraps internal spans with a blackhole span (muted); when enabled, creates `couchbase.internal` spans.
- **Inference**: Enables/disables internal Couchbase spans produced by Couchbase's request tracer integration.

### `DD_TRACE_DBSTATEMENTRULE_ENABLED` (A)

- **Mapping**: `DD_TRACE_DBSTATEMENTRULE_ENABLED` ↔ `Config.isRuleEnabled("DBStatementRule", true)` which reads `trace.dbstatementrule.enabled` (and `trace.DBStatementRule.enabled`).
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/RuleFlags.java:14,43-50`: `DB_STATEMENT("DBStatementRule")` feature is enabled/disabled via `Config.isRuleEnabled(...)`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5198-5203`: rule enablement keys are `trace.<RuleName>.enabled` and `trace.<rulename>.enabled`.
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/TagInterceptor.java:134-136,254-262`: `db.statement` is intercepted and used to set resource name (tag interceptor behavior).
- **Inference**: Toggle for the internal DB statement tag-interceptor rule.

### `DD_TRACE_DB_CLIENT_SPLIT_BY_INSTANCE_TYPE_SUFFIX` (A)

- **Mapping**: `DD_TRACE_DB_CLIENT_SPLIT_BY_INSTANCE_TYPE_SUFFIX` ↔ `TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX` (`"trace.db.client.split-by-instance.type.suffix"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:72`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1676-1679,3299-3301`: reads/exposes `isDbClientSplitByInstanceTypeSuffix()`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/DatabaseClientDecorator.java:105-112`: when splitting by instance, appends `-<dbType>` to the per-instance service name when this toggle is enabled.
- **Inference**: Changes DB per-instance service naming from `<instance>` to `<instance>-<dbType>`.

### `DD_TRACE_ELASTICSEARCH_BODY_AND_PARAMS_ENABLED` (A)

- **Mapping**: `DD_TRACE_ELASTICSEARCH_BODY_AND_PARAMS_ENABLED` ↔ `TraceInstrumentationConfig.ELASTICSEARCH_BODY_AND_PARAMS_ENABLED` (`"trace.elasticsearch.body-and-params.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:297`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1422-1424,4684-4686`: reads/exposes `isElasticsearchBodyAndParamsEnabled()`.
  - `dd-java-agent/instrumentation/elasticsearch/elasticsearch-common/src/main/java/datadog/trace/instrumentation/elasticsearch/ElasticsearchRestClientDecorator.java:98-127`: when enabled, tags both request body and query parameters.
  - `dd-java-agent/instrumentation/opensearch/opensearch-common/src/main/java/datadog/trace/instrumentation/opensearch/OpensearchRestClientDecorator.java:96-127`: same toggle also controls OpenSearch tagging.
- **Inference**: Enables capturing both body and query parameters for Elasticsearch/OpenSearch REST client spans.

### `DD_TRACE_EXECUTORS_ALL` (A)

- **Mapping**: `DD_TRACE_EXECUTORS_ALL` ↔ `TraceInstrumentationConfig.TRACE_EXECUTORS_ALL` (`"trace.executors.all"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:233`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:282,474-475`: reads/exposes `isTraceExecutorsAll()`.
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/executor/AbstractExecutorInstrumentation.java:20-25`: controls whether to widen matching beyond known types (instrument all executors).
- **Inference**: When enabled, instruments all `Executor` implementations for context propagation.

### `DD_TRACE_EXECUTORS` (A)

- **Mapping**: `DD_TRACE_EXECUTORS` ↔ `TraceInstrumentationConfig.TRACE_EXECUTORS` (`"trace.executors"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:283,477-479`: reads/exposes `getTraceExecutors()` list.
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/executor/AbstractExecutorInstrumentation.java:37-40`: uses the list as `configuredMatchingTypes()` so additional executors can be instrumented.
- **Inference**: Allows specifying additional executor types to instrument for async context propagation.

### `DD_TRACE_EXPERIMENTAL_JDBC_POOL_WAITING_ENABLED` (A)

- **Mapping**: `DD_TRACE_EXPERIMENTAL_JDBC_POOL_WAITING_ENABLED` ↔ `TraceInstrumentationConfig.JDBC_POOL_WAITING_ENABLED` (`"trace.experimental.jdbc.pool.waiting.enabled"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:290,493-494`: reads/exposes `isJdbcPoolWaitingEnabled()` (default `false`).
  - `dd-java-agent/instrumentation/jdbc/src/main/java/datadog/trace/instrumentation/jdbc/HikariConcurrentBagInstrumentation.java:44-47,89-101`: when enabled, creates a `pool.waiting` span only when pool blocking is detected (resource `hikari.waiting`, tags include `db.pool.name` when available).
  - `dd-java-agent/instrumentation/jdbc/src/main/java/datadog/trace/instrumentation/jdbc/PoolWaitingDecorator.java:7-10,18-21`: defines span name `pool.waiting` and component `java-jdbc-pool-waiting`.
- **Inference**: Enables emitting `pool.waiting` spans to measure time spent waiting for a JDBC connection from a pool.

### `DD_TRACE_EXPERIMENTAL_JEE_SPLIT_BY_DEPLOYMENT` (A)

- **Mapping**: `DD_TRACE_EXPERIMENTAL_JEE_SPLIT_BY_DEPLOYMENT` ↔ `TraceInstrumentationConfig.EXPERIMENTATAL_JEE_SPLIT_BY_DEPLOYMENT` (`"trace.experimental.jee.split-by-deployment"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1704-1706,3319-3321`: reads/exposes `isJeeSplitByDeployment()` (default `false`).
  - `internal-api/src/main/java/datadog/trace/api/ClassloaderConfigurationOverrides.java:21-23,133-140`: when enabled (and service name is not user-set), can override span service name using per-classloader contextual service name.
  - `internal-api/src/main/java/datadog/trace/api/naming/v0/MessagingNamingV0.java:73-77`: when enabled (and not legacy tracing), messaging service naming can depend on the context classloader’s contextual service name.
  - `dd-java-agent/instrumentation/liberty/liberty-23.0/src/main/java/datadog/trace/instrumentation/liberty23/LibertyServerInstrumentation.java:114-123`: when enabled, enriches server spans using the webapp classloader contextual info.
- **Inference**: Splits service naming by deployment/webapp (classloader) to report separate services per deployment.

### `DD_TRACE_EXPERIMENTAL_KEEP_LATENCY_THRESHOLD_MS` (A)

- **Mapping**: `DD_TRACE_EXPERIMENTAL_KEEP_LATENCY_THRESHOLD_MS` ↔ `TracerConfig.TRACE_KEEP_LATENCY_THRESHOLD_MS` (`"trace.experimental.keep.latency.threshold.ms"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:78`: default is `0` (disabled).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1724-1728,3343-3344`: enables the feature only when partial flush is disabled and threshold is > 0.
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:855-856`: when enabled, registers `LatencyTraceInterceptor`.
  - `dd-trace-core/src/main/java/datadog/trace/core/traceinterceptor/LatencyTraceInterceptor.java:24,39-43`: if local root span duration exceeds threshold, sets `manual.keep=true` on the root span (forces keep).
- **Inference**: Forces keeping “slow” traces whose local root duration exceeds the configured threshold.

### `DD_TRACE_EXPERIMENTAL_LONG_RUNNING_ENABLED` (A)

- **Mapping**: `DD_TRACE_EXPERIMENTAL_LONG_RUNNING_ENABLED` ↔ `TracerConfig.TRACE_LONG_RUNNING_ENABLED` (`"trace.experimental.long-running.enabled"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:285`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2780-2814,3103-3105`: reads/exposes `isLongRunningTraceEnabled()` and flush intervals.
  - `dd-trace-core/src/main/java/datadog/trace/core/PendingTraceBuffer.java:299-304`: creates a `LongRunningTracesTracker` when enabled.
  - `dd-trace-core/src/main/java/datadog/trace/core/LongRunningTracesTracker.java:81-101`: periodically writes running spans only when the Agent supports long-running traces.
- **Inference**: Enables periodic “running span” flushes for long-running traces (when supported by the Datadog Agent).

### `DD_TRACE_EXPERIMENTAL_LONG_RUNNING_INITIAL_FLUSH_INTERVAL` (A)

- **Mapping**: `DD_TRACE_EXPERIMENTAL_LONG_RUNNING_INITIAL_FLUSH_INTERVAL` ↔ `TracerConfig.TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL` (`"trace.experimental.long-running.initial.flush.interval"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:286`: default is `20` seconds.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2793-2802`: validates configured value (must be 10–450 seconds).
  - `dd-trace-core/src/main/java/datadog/trace/core/LongRunningTracesTracker.java:38-40,117-120`: uses the value for the initial flush schedule.
- **Inference**: Controls how long a trace must run before the first running-span flush.

### `DD_TRACE_EXPERIMENTAL_LONG_RUNNING_FLUSH_INTERVAL` (A)

- **Mapping**: `DD_TRACE_EXPERIMENTAL_LONG_RUNNING_FLUSH_INTERVAL` ↔ `TracerConfig.TRACE_LONG_RUNNING_FLUSH_INTERVAL` (`"trace.experimental.long-running.flush.interval"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:287`: default is `120` seconds.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2803-2810`: validates configured value (must be 20–450 seconds).
  - `dd-trace-core/src/main/java/datadog/trace/core/LongRunningTracesTracker.java:40-41,122-123`: uses the value for subsequent periodic flushes.
- **Inference**: Controls how frequently running-span flushes are emitted after the first flush.

### `DD_TRACE_FILEITEMITERATOR_ENABLED` (A) — **unknown**

- **Evidence**:
  - Present in `metadata/supported-configurations.json` (default `true`).
  - No code references found for the expected integration/property keys (e.g., `trace.fileitemiterator.enabled`) and no `InstrumenterModule` was found that uses an integration name like `fileitemiterator` (the commons-fileupload IAST module uses `commons-fileupload`).
- **Outcome**: Added to `workspace/result/unknown_configurations.json`.

### `DD_TRACE_FILEITEMSTREAM_ENABLED` (A) — **unknown**

- **Evidence**:
  - Present in `metadata/supported-configurations.json` (default `true`).
  - No code references found for the expected integration/property keys (e.g., `trace.fileitemstream.enabled`) and no `InstrumenterModule` was found that uses an integration name like `fileitemstream` (the commons-fileupload IAST module uses `commons-fileupload`).
- **Outcome**: Added to `workspace/result/unknown_configurations.json`.

### `DD_TRACE_FILEITEM_ENABLED` (A) — **unknown**

- **Evidence**:
  - Present in `metadata/supported-configurations.json` (default `true`).
  - No code references found for the expected integration/property keys (e.g., `trace.fileitem.enabled`) and no `InstrumenterModule` was found that uses an integration name like `fileitem` (the commons-fileupload IAST module uses `commons-fileupload`).
- **Outcome**: Added to `workspace/result/unknown_configurations.json`.

### `DD_TRACE_FJP_ENABLED` (A)

- **Mapping**: `DD_TRACE_FJP_ENABLED` ↔ integration enablement for `fjp` (e.g., `trace.fjp.enabled` / `integration.fjp.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/ConcurrentInstrumentationNames.java:6`: fork-join integration name is `fjp`.
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/forkjoin/ForkJoinModule.java:23-25`: registers `fjp` under the `java_concurrent` module.
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/forkjoin/JavaForkJoinTaskInstrumentation.java:55-66,69-75`: captures and activates tracing context around `ForkJoinTask` execution/fork, enabling async context propagation.
- **Inference**: Toggles ForkJoinPool/ForkJoinTask context propagation instrumentation.

### `DD_TRACE_FJP_WORKQUEUE_ENABLED` (A)

- **Mapping**: `DD_TRACE_FJP_WORKQUEUE_ENABLED` ↔ integration enablement for `fjp-workqueue` (e.g., `trace.fjp-workqueue.enabled` / `integration.fjp-workqueue.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/forkjoin/JavaForkJoinWorkQueueInstrumentation.java:33-38`: registers additional integration name `fjp-workqueue`.
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/forkjoin/JavaForkJoinWorkQueueInstrumentation.java:84-86`: uses `QueueTimerHelper.startQueuingTimer(...)` when tasks are pushed to the work queue.
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/forkjoin/JavaForkJoinWorkQueueInstrumentation.java:46-52`: also requires queueing-time profiling to be enabled.
- **Inference**: Toggles ForkJoinPool work-queue queueing-time measurement instrumentation (profiling).

### `DD_TRACE_FLUSH_INTERVAL` (A)

- **Mapping**: `DD_TRACE_FLUSH_INTERVAL` ↔ `TracerConfig.TRACE_FLUSH_INTERVAL` (`"trace.flush.interval"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:289`: default is `1` second.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2838-2840,3115-3117`: reads/exposes `getTraceFlushIntervalSeconds()`.
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/WriterFactory.java:83-85,111-112,165-166`: converts seconds to milliseconds and configures the writer flush interval.
- **Inference**: Controls how frequently trace writers flush/send traces.

### `DD_TRACE_FORCEMANUALDROPTAGINTERCEPTOR_ENABLED` (A)

- **Mapping**: `DD_TRACE_FORCEMANUALDROPTAGINTERCEPTOR_ENABLED` ↔ `Config.isRuleEnabled(\"ForceManualDropTagInterceptor\")` (keys: `trace.ForceManualDropTagInterceptor.enabled` and `trace.forcemanualdroptaginterceptor.enabled`).
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/RuleFlags.java:15,46-49`: `FORCE_MANUAL_DROP` is enabled/disabled via `Config.isRuleEnabled(...)`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5198-5203`: rule enablement key shapes.
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/TagInterceptor.java:149-151,297-309`: when enabled, `manual.drop=true` forces user drop sampling priority.
- **Inference**: Toggle for honoring the `manual.drop` tag to force a trace drop decision.

### `DD_TRACE_FORCEMANUALKEEPTAGINTERCEPTOR_ENABLED` (A) — **unknown**

- **Evidence**:
  - Present in `metadata/supported-configurations.json` (default `true`) and `RuleFlags` defines `FORCE_MANUAL_KEEP(\"ForceManualKeepTagInterceptor\")`.
  - No runtime usage sites were found that consult the `FORCE_MANUAL_KEEP` flag; `TagInterceptor` always honors manual keep via `DDTags.MANUAL_KEEP` without checking any rule flag.
- **Outcome**: Added to `workspace/result/unknown_configurations.json`.

### `DD_TRACE_FORCESAMPLINGPRIORITYTAGINTERCEPTOR_ENABLED` (A)

- **Mapping**: `DD_TRACE_FORCESAMPLINGPRIORITYTAGINTERCEPTOR_ENABLED` ↔ `Config.isRuleEnabled(\"ForceSamplingPriorityTagInterceptor\")` (keys: `trace.ForceSamplingPriorityTagInterceptor.enabled` and `trace.forcesamplingprioritytaginterceptor.enabled`).
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/RuleFlags.java:17,46-49`: `FORCE_SAMPLING_PRIORITY` is enabled/disabled via `Config.isRuleEnabled(...)`.
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/TagInterceptor.java:158-160,312-320`: when enabled, `sampling.priority` > 0 forces keep; otherwise forces drop.
- **Inference**: Toggle for honoring the `sampling.priority` tag to force keep/drop sampling decisions.

### `DD_TRACE_GIT_METADATA_ENABLED` (A)

- **Mapping**: `DD_TRACE_GIT_METADATA_ENABLED` ↔ `TracerConfig.TRACE_GIT_METADATA_ENABLED` (`\"trace.git.metadata.enabled\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2006,3540-3541`: reads/exposes `isTraceGitMetadataEnabled()` (default `true`).
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:851-853`: registers `GitMetadataTraceInterceptor` when enabled.
  - `dd-trace-core/src/main/java/datadog/trace/common/GitMetadataTraceInterceptor.java:32-35`: sets git repository URL and commit SHA tags on the first/root span.
- **Inference**: Controls whether git repository URL and commit SHA metadata are added to traces.

### `DD_TRACE_GLOBAL_TAGS` (A)

- **Mapping**: `DD_TRACE_GLOBAL_TAGS` ↔ `GeneralConfig.GLOBAL_TAGS` (`\"trace.global.tags\"`) + merged tag maps (`dd.tags`, `dd.trace.tags`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1550-1560`: merges tag maps and stores `this.tags` (global tags), with service-name precedence and environment/version adjustments.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:4964-4970`: `getGlobalTags()` returns the global tags map used broadly.
- **Inference**: Defines global tags applied to all spans (and runtime metrics).

### `DD_TRACE_GOOGLE_PUBSUB_E2E_DURATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_GOOGLE_PUBSUB_E2E_DURATION_ENABLED` ↔ `trace.google-pubsub.e2e.duration.enabled` via `Config.isEndToEndDurationEnabled(..., \"google-pubsub\")`.
- **Evidence**:
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/MessagingClientDecorator.java:12-16,23-27`: when enabled for an instrumentation, calls `span.beginEndToEnd()` in `afterStart`.
  - `dd-java-agent/instrumentation/google-pubsub-1.116/src/main/java/datadog/trace/instrumentation/googlepubsub/PubSubDecorator.java:104-107`: instrumentation names include `google-pubsub`.
  - `dd-java-agent/instrumentation/google-pubsub-1.116/src/main/java/datadog/trace/instrumentation/googlepubsub/PublisherInstrumentation.java:41-45` and `.../MessageReceiverWrapper.java:23-29`: Pub/Sub producer/consumer spans call `afterStart` and are finished normally.
- **Inference**: Enables beginning end-to-end duration tracking on google-pubsub messaging spans (so `record.e2e_duration_ms` can be recorded when spans finish with end-to-end semantics).

### `DD_TRACE_GOOGLE_PUBSUB_IGNORED_GRPC_METHODS` (A)

- **Mapping**: `DD_TRACE_GOOGLE_PUBSUB_IGNORED_GRPC_METHODS` ↔ `TraceInstrumentationConfig.GOOGLE_PUBSUB_IGNORED_GRPC_METHODS` (`\"trace.google-pubsub.ignored.grpc.methods\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2673-2684`: when the google-pubsub integration is enabled, appends default + configured Pub/Sub gRPC methods to the outbound ignored methods set.
  - `dd-trace-api/src/main/java/datadog/trace/api/config/TraceInstrumentationConfig.java:128-129`: defines the config property.
  - `dd-java-agent/instrumentation/google-pubsub-1.116/src/test/groovy/PubSubTest.groovy:137-142`: test config uses it to ignore specific methods and keep traces deterministic.
- **Inference**: Controls which Pub/Sub gRPC methods are ignored by gRPC outbound instrumentation.

### `DD_TRACE_GOOGLE_PUBSUB_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_GOOGLE_PUBSUB_LEGACY_TRACING_ENABLED` ↔ `trace.google-pubsub.legacy.tracing.enabled` via `Config.isGooglePubSubLegacyTracingEnabled()`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5262-5265`: legacy tracing enablement for `google-pubsub` is gated by inferred-services support and `*.legacy.tracing.enabled`.
  - `dd-java-agent/instrumentation/google-pubsub-1.116/src/main/java/datadog/trace/instrumentation/googlepubsub/PubSubDecorator.java:71-87`: legacy tracing toggle influences service naming for producer/consumer spans.
  - `dd-java-agent/instrumentation/google-pubsub-1.116/src/test/groovy/PubSubTest.groovy:311-321`: test disables legacy tracing and expects application service naming.
- **Inference**: Controls legacy vs non-legacy naming/service selection for Pub/Sub spans (affects inferred-service naming).

### `DD_TRACE_GOOGLE_PUBSUB_PUBLISHER_ENABLED` (A) — **unknown**

- **Evidence**:
  - Present in `metadata/supported-configurations.json` (default `true`, aliases include `DD_TRACE_INTEGRATION_GOOGLE_PUBSUB_PUBLISHER_ENABLED` and `DD_INTEGRATION_GOOGLE_PUBSUB_PUBLISHER_ENABLED`).
  - Pub/Sub tracing module uses only the `google-pubsub` integration name and always includes the publisher instrumentation:
    - `dd-java-agent/instrumentation/google-pubsub-1.116/src/main/java/datadog/trace/instrumentation/googlepubsub/GooglePubSubModule.java:20-47` (module name `google-pubsub`; type instrumentations include `PublisherInstrumentation`).
  - No `InstrumenterModule` name, integration name, or property lookups were found for `google-pubsub-publisher` (for example `trace.google-pubsub-publisher.enabled`).
- **Outcome**: Added to `workspace/result/unknown_configurations.json`.

### `DD_TRACE_GOOGLE_PUBSUB_RECEIVER_ENABLED` (A) — **unknown**

- **Evidence**:
  - Present in `metadata/supported-configurations.json` (default `true`, aliases include `DD_TRACE_INTEGRATION_GOOGLE_PUBSUB_RECEIVER_ENABLED` and `DD_INTEGRATION_GOOGLE_PUBSUB_RECEIVER_ENABLED`).
  - Pub/Sub tracing module uses only the `google-pubsub` integration name and always includes the receiver instrumentation:
    - `dd-java-agent/instrumentation/google-pubsub-1.116/src/main/java/datadog/trace/instrumentation/googlepubsub/GooglePubSubModule.java:20-47` (type instrumentations include `ReceiverInstrumentation` / `ReceiverWithAckInstrumentation`).
  - No `InstrumenterModule` name, integration name, or property lookups were found for `google-pubsub-receiver` (for example `trace.google-pubsub-receiver.enabled`).
- **Outcome**: Added to `workspace/result/unknown_configurations.json`.

### `DD_TRACE_GRPC_IGNORED_INBOUND_METHODS` (A)

- **Mapping**: `DD_TRACE_GRPC_IGNORED_INBOUND_METHODS` ↔ `TraceInstrumentationConfig.GRPC_IGNORED_INBOUND_METHODS` (`\"trace.grpc.ignored.inbound.methods\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/TraceInstrumentationConfig.java:125`: defines the config token.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2667-2668,4649-4651`: reads/exposes the ignored inbound methods set.
  - `dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/TracingServerInterceptor.java:61-63`: bypasses tracing when the RPC method name is in the ignored set.
- **Inference**: Configures which gRPC server RPC method names are excluded from inbound tracing.

### `DD_TRACE_GRPC_IGNORED_OUTBOUND_METHODS` (A)

- **Mapping**: `DD_TRACE_GRPC_IGNORED_OUTBOUND_METHODS` ↔ `TraceInstrumentationConfig.GRPC_IGNORED_OUTBOUND_METHODS` (`\"trace.grpc.ignored.outbound.methods\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/TraceInstrumentationConfig.java:126`: defines the config token.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2669-2685,4653-4655`: reads/exposes the ignored outbound methods set (and appends default Pub/Sub methods when `google-pubsub` is enabled).
  - `dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/client/GrpcClientDecorator.java:92-95`: skips starting a client span when the RPC method name is in the ignored set.
- **Inference**: Configures which gRPC client RPC method names are excluded from outbound tracing.

### `DD_TRACE_GRPC_SERVER_CODE_ORIGIN_ENABLED` (A)

- **Mapping**: `DD_TRACE_GRPC_SERVER_CODE_ORIGIN_ENABLED` ↔ integration toggle `grpc-server-code-origin` (keys: `trace.grpc-server-code-origin.enabled`, `trace.integration.grpc-server-code-origin.enabled`, `integration.grpc-server-code-origin.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/GrpcServerModule.java:38-47`: conditionally adds `MethodHandlersInstrumentation` when not running on GraalVM and `grpc-server-code-origin` is enabled.
  - `dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/MethodHandlersInstrumentation.java:39-66`: calls `DebuggerContext.captureCodeOrigin(...)` for gRPC service methods during `$MethodHandlers` construction.
- **Inference**: Enables capturing code-origin metadata for gRPC server service methods.

### `DD_TRACE_GRPC_SERVER_TRIM_PACKAGE_RESOURCE` (A)

- **Mapping**: `DD_TRACE_GRPC_SERVER_TRIM_PACKAGE_RESOURCE` ↔ `TraceInstrumentationConfig.GRPC_SERVER_TRIM_PACKAGE_RESOURCE` (`\"trace.grpc.server.trim-package-resource\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/TraceInstrumentationConfig.java:130-131`: defines the config token.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2686-2687,4657-4659`: reads/exposes the boolean.
  - `dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/GrpcServerDecorator.java:87-94`: when enabled, normalizes the span resource name by trimming the package prefix from the gRPC service name.
- **Inference**: Controls whether gRPC server span resource names strip package prefixes (reducing resource cardinality).

### `DD_TRACE_HEADER_TAGS_LEGACY_PARSING_ENABLED` (A)

- **Mapping**: `DD_TRACE_HEADER_TAGS_LEGACY_PARSING_ENABLED` ↔ `trace.header.tags.legacy.parsing.enabled` via `Config.isEnabled(false, HEADER_TAGS, \".legacy.parsing.enabled\")`.
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/TracerConfig.java:62`: `HEADER_TAGS` token is `\"trace.header.tags\"`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1567-1583`: legacy mode applies `DD_TRACE_HEADER_TAGS` to request headers only, disables response header tags, and ignores `DD_TRACE_REQUEST_HEADER_TAGS` / `DD_TRACE_RESPONSE_HEADER_TAGS`.
- **Inference**: Switches between legacy and modern HTTP header tag configuration parsing/behavior.

### `DD_TRACE_HTTPASYNCCLIENT4_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_HTTPASYNCCLIENT4_LEGACY_TRACING_ENABLED` ↔ `httpasyncclient4.legacy.tracing.enabled` via `Config.isLegacyTracingEnabled(false, \"httpasyncclient4\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5236-5240`: `isLegacyTracingEnabled(...)` resolves `<name>.legacy.tracing.enabled`.
  - `dd-java-agent/instrumentation/apache-httpclient/apache-httpasyncclient-4.0/src/main/java/datadog/trace/instrumentation/apachehttpasyncclient/HostAndRequestAsHttpUriRequest.java:16-31`: toggles how the request URI is built (parse request line directly vs concatenate host + path).
- **Inference**: Compatibility flag for Apache HttpAsyncClient 4 URI handling used by tracing decorators/injectors.

### `DD_TRACE_HTTPCLIENT_REDIRECT_ENABLED` (A) — **unknown**

- **Evidence**:
  - Present in `metadata/supported-configurations.json` (default `true`).
  - No code references were found for expected property keys (for example `trace.httpclient.redirect.enabled`) and no InstrumenterModule or runtime checks consult this setting.
  - Redirect-related instrumentation exists but is applied unconditionally:
    - `dd-java-agent/instrumentation/apache-httpclient/apache-httpasyncclient-4.0/src/main/java/datadog/trace/instrumentation/apachehttpasyncclient/ApacheHttpAsyncClientModule.java:36-40`: always installs `ApacheHttpClientRedirectInstrumentation`.
    - `dd-java-agent/instrumentation/apache-httpclient/apache-httpasyncclient-4.0/src/main/java/datadog/trace/instrumentation/apachehttpasyncclient/ApacheHttpClientRedirectInstrumentation.java:47-87`: always copies propagation headers on redirect.
- **Outcome**: Added to `workspace/result/unknown_configurations.json`.

### `DD_TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH` (A)

- **Mapping**: `DD_TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH` ↔ `TracerConfig.TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH` (`\"trace.http.resource.remove-trailing-slash\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/TracerConfig.java:68-69`: defines the config token.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1628-1631`: reads the boolean.
  - `internal-api/src/main/java/datadog/trace/api/normalize/HttpResourceNames.java:35-41`: when enabled, strips a trailing `/` from the path portion of the resource name (except for root `/`).
- **Inference**: Controls whether trailing slashes are removed from HTTP span resource names.

### `DD_TRACE_HTTP_URL_CONNECTION_CLASS_NAME` (A)

- **Mapping**: `DD_TRACE_HTTP_URL_CONNECTION_CLASS_NAME` ↔ `TraceInstrumentationConfig.HTTP_URL_CONNECTION_CLASS_NAME` (`\"trace.http.url.connection.class.name\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/TraceInstrumentationConfig.java:89-90`: defines the config token.
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:292,497-499`: reads/exposes the configured class name.
  - `dd-java-agent/instrumentation/java/java-net/java-net-1.8/src/main/java/datadog/trace/instrumentation/java/net/HttpUrlConnectionInstrumentation.java:48-51`: uses the configured class name for optional matching of an additional `HttpURLConnection` implementation.
- **Inference**: Allows extending HttpURLConnection tracing to a custom/vendor implementation class.

### `DD_TRACE_INFERRED_PROXY_SERVICES_ENABLED` (A)

- **Mapping**: `DD_TRACE_INFERRED_PROXY_SERVICES_ENABLED` ↔ `TracerConfig.TRACE_INFERRED_PROXY_SERVICES_ENABLED` (`\"trace.inferred.proxy.services.enabled\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/TracerConfig.java:106-107`: defines the config token.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1853-1854,3385-3387`: reads/exposes inferred proxy propagation enablement.
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:827-829`: registers `InferredProxyPropagator` when enabled.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpServerDecorator.java:178-185`: starts an `InferredProxySpan` as the parent context when enabled and present in context.
  - `dd-trace-core/src/main/java/datadog/trace/core/propagation/InferredProxyPropagator.java:15-35`: extracts `x-dd-proxy*` headers into context (extract-only).
  - `internal-api/src/main/java/datadog/trace/api/gateway/InferredProxySpan.java:24-119`: validates headers and starts an inferred proxy span (currently supports `aws-apigateway`), setting tags/resource from proxy headers.
- **Inference**: Enables inferred proxy span extraction/creation from inbound `x-dd-proxy*` headers.

### `DD_TRACE_INTEGRATION_DATANUCLEUS_MATCHING_SHORTCUT_ENABLED` (A)

- **Mapping**: `DD_TRACE_INTEGRATION_DATANUCLEUS_MATCHING_SHORTCUT_ENABLED` ↔ `InstrumenterConfig.isIntegrationShortcutMatchingEnabled([\"datanucleus\"], false)` (key: `dd.integration.datanucleus.matching.shortcut.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/datanucleus-4.0.5/src/main/java/datadog/trace/instrumentation/datanucleus/ExecutionContextInstrumentation.java:28-37`: only matches known types when shortcut matching is enabled.
- **Inference**: Controls whether Datanucleus instrumentation uses known-types-only matching vs hierarchy matching.

### `DD_TRACE_INTEGRATION_DROPWIZARD_MATCHING_SHORTCUT_ENABLED` (A)

- **Mapping**: `DD_TRACE_INTEGRATION_DROPWIZARD_MATCHING_SHORTCUT_ENABLED` ↔ `InstrumenterModule.isShortcutMatchingEnabled(true)` for integration `dropwizard` (key: `dd.integration.dropwizard.matching.shortcut.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/dropwizard/dropwizard-views-0.7/src/main/java/datadog/trace/instrumentation/dropwizard/view/DropwizardViewInstrumentation.java:31-52`: `onlyMatchKnownTypes()` is controlled by shortcut matching.
- **Inference**: Controls whether Dropwizard view renderer instrumentation uses known-types-only matching vs hierarchy matching.

### `DD_TRACE_INTEGRATION_GRPC_MATCHING_SHORTCUT_ENABLED` (A)

- **Mapping**: `DD_TRACE_INTEGRATION_GRPC_MATCHING_SHORTCUT_ENABLED` ↔ `InstrumenterConfig.isIntegrationShortcutMatchingEnabled([\"grpc\",\"grpc-server\"], true)` (key: `dd.integration.grpc.matching.shortcut.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/grpc-1.5/src/main/java/datadog/trace/instrumentation/grpc/server/GrpcServerBuilderInstrumentation.java:24-50`: only matches known `ServerBuilder` types when shortcut matching is enabled.
- **Inference**: Controls whether gRPC server builder instrumentation uses known-types-only matching vs hierarchy matching.

### `DD_TRACE_INTEGRATION_HIBERNATE_MATCHING_SHORTCUT_ENABLED` (A)

- **Mapping**: `DD_TRACE_INTEGRATION_HIBERNATE_MATCHING_SHORTCUT_ENABLED` ↔ `InstrumenterConfig.isIntegrationShortcutMatchingEnabled([\"hibernate\",\"hibernate-core\"], true)` (key: `dd.integration.hibernate.matching.shortcut.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/hibernate/hibernate-core-4.0/src/main/java/datadog/trace/instrumentation/hibernate/core/v4_0/AbstractHibernateInstrumentation.java:11-14`: shortcut matching controls `onlyMatchKnownTypes()` for Hibernate instrumentations.
- **Inference**: Controls whether Hibernate instrumentations use known-types-only matching vs hierarchy matching.

### `DD_TRACE_INTEGRATION_HTTPASYNCCLIENT5_MATCHING_SHORTCUT_ENABLED` (A)

- **Mapping**: `DD_TRACE_INTEGRATION_HTTPASYNCCLIENT5_MATCHING_SHORTCUT_ENABLED` ↔ `InstrumenterModule.isShortcutMatchingEnabled(false)` for integration `httpasyncclient5` (key: `dd.integration.httpasyncclient5.matching.shortcut.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/apache-httpclient/apache-httpclient-5.0/src/main/java/datadog/trace/instrumentation/apachehttpclient5/ApacheHttpAsyncClientInstrumentation.java:37-64`: shortcut matching controls whether the async client instrumentation only matches known types.
- **Inference**: Controls whether Apache HttpAsyncClient 5 instrumentation uses known-types-only matching vs hierarchy matching.

### `DD_TRACE_INTEGRATION_HTTPASYNCCLIENT_MATCHING_SHORTCUT_ENABLED` (A)

- **Mapping**: `DD_TRACE_INTEGRATION_HTTPASYNCCLIENT_MATCHING_SHORTCUT_ENABLED` ↔ `InstrumenterConfig.isIntegrationShortcutMatchingEnabled([\"httpasyncclient\",\"apache-httpasyncclient\"], false)` (key: `dd.integration.httpasyncclient.matching.shortcut.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/apache-httpclient/apache-httpasyncclient-4.0/src/main/java/datadog/trace/instrumentation/apachehttpasyncclient/ApacheHttpAsyncClientInstrumentation.java:32-60`: shortcut matching controls whether the async client instrumentation only matches known types.
- **Inference**: Controls whether Apache HttpAsyncClient instrumentation uses known-types-only matching vs hierarchy matching.

### `DD_TRACE_INTEGRATION_HTTPCLIENT5_MATCHING_SHORTCUT_ENABLED` (A)

- **Mapping**: `DD_TRACE_INTEGRATION_HTTPCLIENT5_MATCHING_SHORTCUT_ENABLED` ↔ `InstrumenterModule.isShortcutMatchingEnabled(false)` for integration `httpclient5` (key: `dd.integration.httpclient5.matching.shortcut.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/apache-httpclient/apache-httpclient-5.0/src/main/java/datadog/trace/instrumentation/apachehttpclient5/ApacheHttpClientInstrumentation.java:37-58`: shortcut matching controls whether the HttpClient 5 instrumentation only matches known types.
- **Inference**: Controls whether Apache HttpClient 5 instrumentation uses known-types-only matching vs hierarchy matching.

### `DD_TRACE_INTEGRATION_HTTPCLIENT_MATCHING_SHORTCUT_ENABLED` (A)

- **Mapping**: `DD_TRACE_INTEGRATION_HTTPCLIENT_MATCHING_SHORTCUT_ENABLED` ↔ `InstrumenterModule.isShortcutMatchingEnabled(false)` for integration `httpclient` (key: `dd.integration.httpclient.matching.shortcut.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/apache-httpclient/apache-httpclient-4.0/src/main/java/datadog/trace/instrumentation/apachehttpclient/ApacheHttpClientInstrumentation.java:47-65`: shortcut matching controls whether the HttpClient instrumentation only matches known types.
- **Inference**: Controls whether Apache HttpClient instrumentation uses known-types-only matching vs hierarchy matching.

### `DD_TRACE_INTEGRATION_JAVA_CONCURRENT_MATCHING_SHORTCUT_ENABLED` (A)

- **Mapping**: `DD_TRACE_INTEGRATION_JAVA_CONCURRENT_MATCHING_SHORTCUT_ENABLED` ↔ `InstrumenterConfig.isIntegrationShortcutMatchingEnabled([\"java_concurrent\", ...], false)` (key: `dd.integration.java_concurrent.matching.shortcut.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/executor/RejectedExecutionHandlerInstrumentation.java:30-56`: shortcut matching controls whether the rejected-execution-handler instrumentation only matches known types vs uses interface matching.
- **Inference**: Controls whether java concurrent instrumentations use known-types-only matching vs hierarchy matching (startup/perf tradeoff).

### `DD_TRACE_INTEGRATION_OPENTELEMETRY_EXPERIMENTAL_MATCHING_SHORTCUT_ENABLED` (A)

- **Mapping**: `DD_TRACE_INTEGRATION_OPENTELEMETRY_EXPERIMENTAL_MATCHING_SHORTCUT_ENABLED` ↔ `InstrumenterModule.isShortcutMatchingEnabled(false)` for integration `opentelemetry.experimental` (key: `dd.integration.opentelemetry.experimental.matching.shortcut.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/opentelemetry/opentelemetry-1.4/src/main/java/datadog/trace/instrumentation/opentelemetry14/OpenTelemetryInstrumentation.java:44-55`: shortcut matching controls whether only known `OpenTelemetry` types are matched.
- **Inference**: Controls whether OpenTelemetry experimental instrumentation uses known-types-only matching vs hierarchy matching.

### `DD_TRACE_INTERNAL_EXIT_ON_FAILURE` (A)

- **Mapping**: `DD_TRACE_INTERNAL_EXIT_ON_FAILURE` ↔ `GeneralConfig.INTERNAL_EXIT_ON_FAILURE` (`\"trace.internal.exit.on.failure\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/GeneralConfig.java:77`: defines the config token.
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:347,655-656`: reads/exposes the boolean.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/bytebuddy/ExceptionHandlers.java:38-111`: when enabled, logs instrumentation handler failures at error and calls `System.exit(1)`; otherwise logs at debug and continues.
- **Inference**: Fail-fast toggle for instrumentation exception handling (intended for internal/debug use).

### `DD_TRACE_JAVAX_WEBSOCKET_ENABLED` (A)

- **Mapping**: `DD_TRACE_JAVAX_WEBSOCKET_ENABLED` ↔ integration toggle `javax-websocket` (keys: `trace.javax-websocket.enabled`, `trace.integration.javax-websocket.enabled`, `integration.javax-websocket.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/websocket/javax-websocket-1.0/src/main/java/datadog/trace/instrumentation/websocket/jsr256/JavaxWebsocketModule.java:17-24`: module registers under `javax-websocket` (and `websocket`) and is enabled/disabled via integration enablement.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/WebsocketDecorator.java:65-123`: websocket message spans are created for receive/send/close and finished with websocket message tags.
- **Inference**: Toggles Javax WebSocket instrumentation (tracing websocket message send/receive/close events).

### `DD_TRACE_JAVA_CONCURRENT_OTHER_ENABLED` (A)

- **Mapping**: `DD_TRACE_JAVA_CONCURRENT_OTHER_ENABLED` ↔ integration toggle `java_concurrent.other` (key: `trace.java_concurrent.other.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/executor/ExecutorModule.java:64-70`: installs `NonStandardExecutorInstrumentation` only when `InstrumenterConfig.isIntegrationEnabled([\"java_concurrent.other\"], true)` is true.
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/executor/NonStandardExecutorInstrumentation.java:10-18`: instruments non-standard `dispatch(...)` methods to propagate context.
- **Inference**: Controls whether additional/non-standard executor implementations are instrumented for async context propagation.

### `DD_TRACE_JAX_RS_ADDITIONAL_ANNOTATIONS` (A)

- **Mapping**: `DD_TRACE_JAX_RS_ADDITIONAL_ANNOTATIONS` ↔ `TraceInstrumentationConfig.JAX_RS_ADDITIONAL_ANNOTATIONS` (`\"trace.jax-rs.additional.annotations\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:349-350,621-622`: reads/exposes the configured additional JAX-RS annotations list.
  - `dd-java-agent/instrumentation/rs/jax-rs/jax-rs-annotations/jax-rs-annotations-2.0/src/main/java/datadog/trace/instrumentation/jaxrs2/JaxRsAnnotationsInstrumentation.java:47-58`: adds the configured annotations to the default set used to match JAX-RS resource methods.
- **Inference**: Lets users extend which annotations are treated as JAX-RS endpoint annotations for tracing.

### `DD_TRACE_JAX_RS_EXCEPTION_AS_ERROR_ENABLED` (A)

- **Mapping**: `DD_TRACE_JAX_RS_EXCEPTION_AS_ERROR_ENABLED` ↔ `TraceInstrumentationConfig.JAX_RS_EXCEPTION_AS_ERROR_ENABLED` (`\"trace.jax-rs.exception-as-error.enabled\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2823-2825,4697-4699`: reads/exposes `isJaxRsExceptionAsErrorEnabled()` (default `true`).
  - `dd-java-agent/instrumentation/jersey/jersey-client-2.0/src/main/java/org/glassfish/jersey/client/WrappingResponseCallback.java:30-41`: sets `span.setError(...)` based on `Config.get().isJaxRsExceptionAsErrorEnabled()` when a `ProcessingException` happens.
  - `dd-java-agent/instrumentation/resteasy/resteasy-3.0/src/main/java/datadog/trace/instrumentation/connection_error/resteasy/ResteasyClientConnectionErrorInstrumentation.java:65-71` and `.../WrappedFuture.java:58-69`: same behavior for RESTEasy client errors.
- **Inference**: Controls whether client-side JAX-RS exceptions mark spans as errors.

### `DD_TRACE_JDBC_CONNECTION_CLASS_NAME` (A)

- **Mapping**: `DD_TRACE_JDBC_CONNECTION_CLASS_NAME` ↔ `TraceInstrumentationConfig.JDBC_CONNECTION_CLASS_NAME` (`\"trace.jdbc.connection.class.name\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:289,489-490`: reads/exposes `getJdbcConnectionClassName()`.
  - `dd-java-agent/instrumentation/jdbc/src/main/java/datadog/trace/instrumentation/jdbc/DefaultConnectionInstrumentation.java:55-59`: uses the configured class name for optional matching (`ForConfiguredType`).
- **Inference**: Allows JDBC tracing to target a custom/vendor Connection implementation class.

### `DD_TRACE_JDBC_PREPARED_STATEMENT_CLASS_NAME` (A)

- **Mapping**: `DD_TRACE_JDBC_PREPARED_STATEMENT_CLASS_NAME` ↔ `TraceInstrumentationConfig.JDBC_PREPARED_STATEMENT_CLASS_NAME` (`\"trace.jdbc.prepared.statement.class.name\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:287-288,485-487`: reads/exposes `getJdbcPreparedStatementClassName()`.
  - `dd-java-agent/instrumentation/jdbc/src/main/java/datadog/trace/instrumentation/jdbc/PreparedStatementInstrumentation.java:134-138`: uses the configured class name for optional matching (`ForConfiguredType`).
- **Inference**: Allows JDBC tracing to target a custom/vendor PreparedStatement/CallableStatement implementation class.

### `DD_TRACE_JMS_1_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMS_1_ENABLED` ↔ integration toggle `jms-1` (keys: `trace.jms-1.enabled`, `trace.integration.jms-1.enabled`, `integration.jms-1.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/JavaxJmsModule.java:18-24`: JMS module is registered as `jms` with aliases `jms-1` and `jms-2`.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java:70-77` + `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:391-409`: module enablement is resolved via `InstrumenterConfig.isIntegrationEnabled(...)` using `trace.<name>.enabled` (and aliases).
- **Inference**: Controls whether the JMS instrumentation module can be enabled/disabled via the `jms-1` integration name.

### `DD_TRACE_JMS_2_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMS_2_ENABLED` ↔ integration toggle `jms-2` (keys: `trace.jms-2.enabled`, `trace.integration.jms-2.enabled`, `integration.jms-2.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/JavaxJmsModule.java:18-24`: JMS module is registered as `jms` with aliases `jms-1` and `jms-2`.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java:70-77` + `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:391-409`: module enablement is resolved via `InstrumenterConfig.isIntegrationEnabled(...)` using `trace.<name>.enabled` (and aliases).
- **Inference**: Controls whether the JMS instrumentation module can be enabled/disabled via the `jms-2` integration name.

### `DD_TRACE_JMS_E2E_DURATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMS_E2E_DURATION_ENABLED` ↔ `jms.e2e.duration.enabled` via `Config.isEndToEndDurationEnabled(..., \"jms\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5218-5222`: `.e2e.duration.enabled` toggles are resolved via `ConfigProvider.isEnabled(...)`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/MessagingClientDecorator.java:10-27`: when enabled for the instrumentation name, calls `span.beginEndToEnd()` in `afterStart`.
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/JMSDecorator.java:133-136`: instrumentation name for the decorator is `jms`.
- **Inference**: Enables beginning end-to-end duration tracking on JMS messaging spans.

### `DD_TRACE_JMS_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMS_LEGACY_TRACING_ENABLED` ↔ `jms.legacy.tracing.enabled` via `Config.isJmsLegacyTracingEnabled()`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5252-5255`: legacy tracing for `jms` is gated by inferred-services support and `*.legacy.tracing.enabled`.
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/JMSDecorator.java:41,66-84`: legacy tracing flag influences how JMS producer/consumer service names are computed.
- **Inference**: Controls legacy vs non-legacy naming/service selection for JMS spans.

### `DD_TRACE_JMS_PROPAGATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMS_PROPAGATION_ENABLED` ↔ `jms.propagation.enabled` via `Config.isPropagationEnabled(true, \"jms\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2649,4471-4473`: reads/exposes `isJmsPropagationEnabled()`.
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/JMSMessageProducerInstrumentation.java:115-118`: injects trace context into messages only when propagation is enabled.
- **Inference**: Enables/disables JMS trace-context propagation through message headers/properties.

### `DD_TRACE_JMS_TIME_IN_QUEUE_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMS_TIME_IN_QUEUE_ENABLED` ↔ `jms.time-in-queue.enabled` via `Config.isTimeInQueueEnabled(..., \"jms\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5267-5272`: `.time-in-queue.enabled` toggles are gated by inferred-services support.
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/JMSDecorator.java:41-45`: computes `TIME_IN_QUEUE_ENABLED` from config (`isTimeInQueueEnabled(...)`).
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/JMSMessageProducerInstrumentation.java:120-124`: injects time-in-queue metadata into messages when enabled.
  - `dd-java-agent/instrumentation/jms/javax-jms-1.1/src/main/java/datadog/trace/instrumentation/jms/JMSMessageConsumerInstrumentation.java:132-149`: when enabled and metadata is present, starts a `jms.deliver` span (time-in-queue) and parents the consume span to it.
- **Inference**: Enables recording broker time-in-queue for JMS messages (adds a `jms.deliver` span and ties consume spans to it).

### `DD_TRACE_JMXFETCH_ACTIVEMQ_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_ACTIVEMQ_ENABLED` ↔ `trace.jmxfetch.activemq.enabled` (alias: `jmxfetch.activemq.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(integrationNames, \"jmxfetch.\", \".enabled\", defaultEnabled)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: `isEnabled(...)` checks both `trace.<configKey>` and `<configKey>` as an alias.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: skips an internal metric config when its `jmxfetch.<name>.enabled` toggle is false.
- **Inference**: Controls whether the built-in JMXFetch metric config for ActiveMQ is loaded/run (when JMXFetch itself is enabled).

### `DD_TRACE_JMXFETCH_CONFLUENT_PLATFORM_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_CONFLUENT_PLATFORM_ENABLED` ↔ `trace.jmxfetch.confluent_platform.enabled` (alias: `jmxfetch.confluent_platform.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(...)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: checks both trace-prefixed and non-trace keys.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: skips an internal metric config when disabled.
- **Inference**: Controls whether the built-in JMXFetch metric config for Confluent Platform is loaded/run (when JMXFetch itself is enabled).

### `DD_TRACE_JMXFETCH_HIVEMQ_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_HIVEMQ_ENABLED` ↔ `trace.jmxfetch.hivemq.enabled` (alias: `jmxfetch.hivemq.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(...)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: checks both trace-prefixed and non-trace keys.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: skips an internal metric config when disabled.
- **Inference**: Controls whether the built-in JMXFetch metric config for HiveMQ is loaded/run (when JMXFetch itself is enabled).

### `DD_TRACE_JMXFETCH_HIVE_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_HIVE_ENABLED` ↔ `trace.jmxfetch.hive.enabled` (alias: `jmxfetch.hive.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(...)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: checks both trace-prefixed and non-trace keys.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: skips an internal metric config when disabled.
- **Inference**: Controls whether the built-in JMXFetch metric config for Hive is loaded/run (when JMXFetch itself is enabled).

### `DD_TRACE_JMXFETCH_HUDI_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_HUDI_ENABLED` ↔ `trace.jmxfetch.hudi.enabled` (alias: `jmxfetch.hudi.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(...)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: checks both trace-prefixed and non-trace keys.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: skips an internal metric config when disabled.
- **Inference**: Controls whether the built-in JMXFetch metric config for Hudi is loaded/run (when JMXFetch itself is enabled).

### `DD_TRACE_JMXFETCH_JBOSS_WILDFLY_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_JBOSS_WILDFLY_ENABLED` ↔ `trace.jmxfetch.jboss_wildfly.enabled` (alias: `jmxfetch.jboss_wildfly.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(...)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: checks both trace-prefixed and non-trace keys.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: skips an internal metric config when disabled.
- **Inference**: Controls whether the built-in JMXFetch metric config for JBoss/WildFly is loaded/run (when JMXFetch itself is enabled).

### `DD_TRACE_JMXFETCH_KUBE_APISERVER_METRICS_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_KUBE_APISERVER_METRICS_ENABLED` ↔ `trace.jmxfetch.kube_apiserver_metrics.enabled` (alias: `jmxfetch.kube_apiserver_metrics.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(...)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: checks both trace-prefixed and non-trace keys.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: skips an internal metric config when disabled.
- **Inference**: Controls whether the built-in JMXFetch metric config for Kubernetes API server metrics is loaded/run (when JMXFetch itself is enabled).

### `DD_TRACE_JMXFETCH_PRESTO_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_PRESTO_ENABLED` ↔ `trace.jmxfetch.presto.enabled` (alias: `jmxfetch.presto.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(...)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: checks both trace-prefixed and non-trace keys.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: skips an internal metric config when disabled.
- **Inference**: Controls whether the built-in JMXFetch metric config for Presto is loaded/run (when JMXFetch itself is enabled).

### `DD_TRACE_JMXFETCH_SOLR_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_SOLR_ENABLED` ↔ `trace.jmxfetch.solr.enabled` (alias: `jmxfetch.solr.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(...)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: checks both trace-prefixed and non-trace keys.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: skips an internal metric config when disabled.
- **Inference**: Controls whether the built-in JMXFetch metric config for Solr is loaded/run (when JMXFetch itself is enabled).

### `DD_TRACE_JMXFETCH_SONARQUBE_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_SONARQUBE_ENABLED` ↔ `trace.jmxfetch.sonarqube.enabled` (alias: `jmxfetch.sonarqube.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(...)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: checks both trace-prefixed and non-trace keys.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: skips an internal metric config when disabled.
- **Inference**: Controls whether the built-in JMXFetch metric config for SonarQube is loaded/run (when JMXFetch itself is enabled).

### `DD_TRACE_JMXFETCH_WEBLOGIC_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_WEBLOGIC_ENABLED` ↔ `trace.jmxfetch.weblogic.enabled` (alias: `jmxfetch.weblogic.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(...)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: checks both trace-prefixed and non-trace keys.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: skips an internal metric config when disabled.
- **Inference**: Controls whether the built-in JMXFetch metric config for WebLogic is loaded/run (when JMXFetch itself is enabled).

### `DD_TRACE_JMXFETCH_WEBSPHERE_ENABLED` (A)

- **Mapping**: `DD_TRACE_JMXFETCH_WEBSPHERE_ENABLED` ↔ `trace.jmxfetch.websphere.enabled` (alias: `jmxfetch.websphere.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(...)`.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:99-101`: includes the WebSphere JMXFetch config file (`jmxfetch-websphere-config.yaml`) when the `websphere` JMXFetch integration is enabled.
- **Inference**: Controls whether the extra WebSphere JMXFetch configuration is enabled.

### `DD_TRACE_JMXFETCH_{CHECK_NAME}_ENABLED` (B)

- **Mapping**: `DD_TRACE_JMXFETCH_{CHECK_NAME}_ENABLED` ↔ `trace.jmxfetch.{check_name}.enabled` (alias: `jmxfetch.{check_name}.enabled`) via `Config.isJmxFetchIntegrationEnabled(...)`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5157-5160`: JMXFetch integration toggles are resolved via `configProvider.isEnabled(integrationNames, \"jmxfetch.\", \".enabled\", defaultEnabled)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: `isEnabled(...)` checks both `trace.<configKey>` and `<configKey>`.
  - `dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java:191-197`: uses `isJmxFetchIntegrationEnabled(...)` to decide whether to skip an internal metric config (derived from a config filename).
- **Inference**: Template for per-check JMXFetch enablement (enables/disables JMXFetch metric configs by check name).

### `DD_TRACE_KAFKA_CLIENT_PROPAGATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_KAFKA_CLIENT_PROPAGATION_ENABLED` ↔ `kafka.client.propagation.enabled` (full key: `trace.kafka.client.propagation.enabled`) and is evaluated alongside `kafka.propagation.enabled` via `Config.isPropagationEnabled(true, \"kafka\", \"kafka.client\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2644,4463-4465`: computes/returns `isKafkaClientPropagationEnabled()`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: `.propagation.enabled` toggles are resolved via `isEnabled(...)`.
  - `dd-java-agent/instrumentation/kafka/kafka-clients-0.11/src/main/java/datadog/trace/instrumentation/kafka_clients/KafkaProducerInstrumentation.java:167-171`: injects context into Kafka headers only when `Config.get().isKafkaClientPropagationEnabled()` is true (and topic is not disabled).
- **Inference**: Controls whether Kafka producer/consumer tracing propagates trace context through Kafka record headers.

### `DD_TRACE_KAFKA_PROPAGATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_KAFKA_PROPAGATION_ENABLED` ↔ `kafka.propagation.enabled` (full key: `trace.kafka.propagation.enabled`) and is evaluated alongside `kafka.client.propagation.enabled` via `Config.isPropagationEnabled(true, \"kafka\", \"kafka.client\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2644,4463-4465`: Kafka propagation enablement is computed from both `kafka` and `kafka.client` names.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/ConfigProvider.java:482-500`: `.propagation.enabled` toggles are resolved via `isEnabled(...)`.
- **Inference**: One of the Kafka propagation toggles; disabling either `kafka.*` or `kafka.client.*` propagation disables Kafka context propagation.

### `DD_TRACE_KAFKA_E2E_DURATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_KAFKA_E2E_DURATION_ENABLED` ↔ `kafka.e2e.duration.enabled` (full key: `trace.kafka.e2e.duration.enabled`) via `Config.isEndToEndDurationEnabled(..., \"kafka\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5218-5222`: `.e2e.duration.enabled` toggles are resolved via `ConfigProvider.isEnabled(...)`.
  - `dd-java-agent/instrumentation/kafka/kafka-clients-0.11/src/main/java/datadog/trace/instrumentation/kafka_clients/KafkaDecorator.java:95-98`: Kafka spans use instrumentation name `kafka`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/MessagingClientDecorator.java:23-27`: when enabled, calls `span.beginEndToEnd()` in `afterStart`.
- **Inference**: Enables beginning end-to-end duration tracking on Kafka messaging spans.

### `DD_TRACE_KAFKA_STREAMS_E2E_DURATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_KAFKA_STREAMS_E2E_DURATION_ENABLED` ↔ `kafka-streams.e2e.duration.enabled` (full key: `trace.kafka-streams.e2e.duration.enabled`) via `Config.isEndToEndDurationEnabled(..., \"kafka\", \"kafka-streams\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5218-5222`: `.e2e.duration.enabled` toggles are resolved via `ConfigProvider.isEnabled(...)`.
  - `dd-java-agent/instrumentation/kafka/kafka-streams-0.11/src/main/java/datadog/trace/instrumentation/kafka_streams/KafkaStreamsDecorator.java:65-68`: Kafka Streams spans use instrumentation names `kafka` and `kafka-streams`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/MessagingClientDecorator.java:23-27`: when enabled, calls `span.beginEndToEnd()` in `afterStart`.
- **Inference**: Enables beginning end-to-end duration tracking on Kafka Streams spans.

### `DD_TRACE_KAFKA_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_KAFKA_LEGACY_TRACING_ENABLED` ↔ `kafka.legacy.tracing.enabled` via `Config.isKafkaLegacyTracingEnabled()`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5257-5259`: legacy tracing enablement for `kafka` is gated by inferred-services support and `*.legacy.tracing.enabled`.
  - `dd-java-agent/instrumentation/kafka/kafka-clients-0.11/src/main/java/datadog/trace/instrumentation/kafka_clients/KafkaDecorator.java:40-42`: legacy tracing flag influences service naming and default time-in-queue behavior.
- **Inference**: Controls legacy vs non-legacy naming/service selection for Kafka spans (affects inferred-service naming).

### `DD_TRACE_KAFKA_TIME_IN_QUEUE_ENABLED` (A)

- **Mapping**: `DD_TRACE_KAFKA_TIME_IN_QUEUE_ENABLED` ↔ `kafka.time-in-queue.enabled` via `Config.isTimeInQueueEnabled(..., \"kafka\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5267-5272`: `.time-in-queue.enabled` toggles are gated by inferred-services support.
  - `dd-java-agent/instrumentation/kafka/kafka-clients-0.11/src/main/java/datadog/trace/instrumentation/kafka_clients/KafkaDecorator.java:40-42`: computes `TIME_IN_QUEUE_ENABLED` from config.
  - `dd-java-agent/instrumentation/kafka/kafka-clients-0.11/src/main/java/datadog/trace/instrumentation/kafka_clients/KafkaProducerInstrumentation.java:205-207`: injects time-in-queue metadata into headers when enabled.
  - `dd-java-agent/instrumentation/kafka/kafka-clients-0.11/src/main/java/datadog/trace/instrumentation/kafka_clients/TracingIterator.java:88-93`: when enabled and metadata is present, starts a `kafka.deliver` span using the extracted start time.
- **Inference**: Enables recording broker time-in-queue for Kafka messages (adds a `kafka.deliver` span and ties consume spans to it).

### `DD_TRACE_LEGACY_E2E_DURATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_LEGACY_E2E_DURATION_ENABLED` ↔ `legacy.e2e.duration.enabled` (full key: `trace.legacy.e2e.duration.enabled`) via `Config.isEndToEndDurationEnabled(false, \"legacy\")`.
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/DDSpan.java:205-216`: when enabled, `beginEndToEnd()` writes a start time into baggage key `t0`; otherwise uses `context.beginEndToEnd()`.
  - `dd-trace-core/src/main/java/datadog/trace/core/DDSpan.java:220-241`: when enabled, `finishWithEndToEnd()` reads baggage key `t0` to compute `record.e2e_duration_ms`; otherwise uses `context.getEndToEndStartTime()`.
- **Inference**: Switches the tracer between legacy baggage-based E2E duration tracking and the newer context-based E2E tracking.

### `DD_TRACE_NATIVE_IMAGE_ENABLED` (A)

- **Mapping**: `DD_TRACE_NATIVE_IMAGE_ENABLED` ↔ integration toggle `native-image` (keys: `trace.native-image.enabled`, `trace.integration.native-image.enabled`, `integration.native-image.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/graal/graal-native-image-20.0/src/main/java/datadog/trace/instrumentation/graal/nativeimage/AbstractNativeImageModule.java:8-22`: module name is `native-image` and enablement is gated by `Platform.isNativeImageBuilder()`.
  - `dd-java-agent/instrumentation/graal/graal-native-image-20.0/src/main/java/datadog/trace/instrumentation/graal/nativeimage/GraalNativeImageModule.java:39-46`: installs native-image builder instrumentations (substitutions/resources/build-time linking).
- **Inference**: Controls whether the native-image builder integrations are enabled when running inside the GraalVM native-image builder.

### `DD_TRACE_PEERSERVICETAGINTERCEPTOR_ENABLED` (A)

- **Mapping**: `DD_TRACE_PEERSERVICETAGINTERCEPTOR_ENABLED` ↔ rule enablement for `PeerServiceTagInterceptor` via `Config.isRuleEnabled(\"PeerServiceTagInterceptor\", false)`.
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/RuleFlags.java:18,46-49`: `PEER_SERVICE` feature is enabled/disabled via `Config.isRuleEnabled(...)` (default `false`).
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/TagInterceptor.java:139-143,286-295`: when enabled, setting `peer.service` causes the span service name to be set to that value.
- **Inference**: Toggle for allowing `peer.service` to override the span service name (via tag interception).

### `DD_TRACE_PEER_HOSTNAME_ENABLED` (A)

- **Mapping**: `DD_TRACE_PEER_HOSTNAME_ENABLED` ↔ `TracerConfig.TRACE_PEER_HOSTNAME_ENABLED` (`\"trace.peer.hostname.enabled\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1606,3199-3201`: reads/exposes `isPeerHostNameEnabled()` (default `true`).
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/BaseDecorator.java:133-143`: when enabled, sets `peer.hostname` on spans when the remote address is resolved.
- **Inference**: Controls whether the tracer sets the resolved `peer.hostname` tag (in addition to peer IP tags).

### `DD_TRACE_PEER_SERVICE_COMPONENT_OVERRIDES` (A)

- **Mapping**: `DD_TRACE_PEER_SERVICE_COMPONENT_OVERRIDES` ↔ `TracerConfig.TRACE_PEER_SERVICE_COMPONENT_OVERRIDES` (`\"trace.peer.service.component.overrides\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1612-1613,3207-3209`: reads/exposes the overrides map.
  - `internal-api/src/main/java/datadog/trace/api/naming/v1/PeerServiceNamingV1.java:56-65`: if an override exists for the span `component`, sets `peer.service` to the override and uses source `_component_override`.
- **Inference**: Allows overriding computed peer.service defaults based on the span component.

### `DD_TRACE_PEER_SERVICE_DEFAULTS_ENABLED` (A)

- **Mapping**: `DD_TRACE_PEER_SERVICE_DEFAULTS_ENABLED` ↔ `TracerConfig.TRACE_PEER_SERVICE_DEFAULTS_ENABLED` (`\"trace.peer.service.defaults.enabled\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1610-1611,3203-3205`: reads/exposes `isPeerServiceDefaultsEnabled()` (default `false`).
  - `internal-api/src/main/java/datadog/trace/api/naming/v0/NamingSchemaV0.java:18-21`: when enabled, uses `PeerServiceNamingV1` to compute defaults; otherwise uses `PeerServiceNamingV0` (no defaults).
- **Inference**: Enables default `peer.service` computation in Naming Schema v0.

### `DD_TRACE_PEER_SERVICE_MAPPING` (A)

- **Mapping**: `DD_TRACE_PEER_SERVICE_MAPPING` ↔ `TracerConfig.TRACE_PEER_SERVICE_MAPPING` (`\"trace.peer.service.mapping\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1620,3215-3217`: reads/exposes the mapping map.
  - `dd-trace-core/src/main/java/datadog/trace/core/tagprocessor/PeerServiceCalculator.java:55-61`: when a mapping exists, rewrites `peer.service` and sets `_dd.peer.service.remapped_from`.
- **Inference**: Allows remapping peer.service values (for example to normalize backend names) while recording the original.

### `DD_TRACE_PERF_METRICS_ENABLED` (A)

- **Mapping**: `DD_TRACE_PERF_METRICS_ENABLED` ↔ `GeneralConfig.PERF_METRICS_ENABLED` (`\"trace.perf.metrics.enabled\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1974-1976,3493-3495`: enables perf metrics only when runtime metrics are enabled.
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:721-725`: enables performance monitoring (`Monitoring`) when `config.isPerfMetricsEnabled()` is true.
- **Inference**: Controls whether the tracer emits performance monitoring metrics/timers (requires runtime metrics enabled).

### `DD_TRACE_PIPE_NAME` (A)

- **Mapping**: `DD_TRACE_PIPE_NAME` ↔ `TracerConfig.AGENT_NAMED_PIPE` (`\"trace.pipe.name\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1524,3155-3157`: reads/exposes the agent named pipe.
  - `communication/src/main/java/datadog/communication/ddagent/SharedCommunicationObjects.java:87-92`: passes the configured named pipe into the agent HTTP client builder.
  - `communication/src/main/java/datadog/communication/http/OkHttpUtils.java:146-152`: when `namedPipe` is set, uses `NamedPipeSocketFactory` for HTTP transport.
- **Inference**: Sets the Windows named pipe used as the transport to communicate with the Datadog Agent (trace intake/proxy).

### `DD_TRACE_PLAY_REPORT_HTTP_STATUS` (A)

- **Mapping**: `DD_TRACE_PLAY_REPORT_HTTP_STATUS` ↔ `TraceInstrumentationConfig.PLAY_REPORT_HTTP_STATUS` (`\"trace.play.report-http-status\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/TraceInstrumentationConfig.java:145`: defines the config token.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2708,4531-4533`: reads/exposes `getPlayReportHttpStatus()` (default `false`).
  - `dd-java-agent/instrumentation/play/play-2.6/src/main/java/datadog/trace/instrumentation/play26/PlayHttpServerDecorator.java:41,218-220`: when enabled, sets HTTP status code `500` on spans when an exception is handled in `onError`.
- **Inference**: Controls whether Play server spans get an explicit HTTP status code on exception paths.

### `DD_TRACE_POST_PROCESSING_TIMEOUT` (A)

- **Mapping**: `DD_TRACE_POST_PROCESSING_TIMEOUT` ↔ `TracerConfig.TRACE_POST_PROCESSING_TIMEOUT` (`\"trace.post-processing.timeout\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:291`: default is `1000` ms.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2843-2845,3119-3121`: reads/exposes `getTracePostProcessingTimeout()`.
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/TraceProcessingWorker.java:255-272`: uses the timeout as a per-trace deadline for span post-processing (`SpanPostProcessor.process(span, timeoutCheck)`).
- **Inference**: Limits the amount of time spent post-processing spans in a trace.

### `DD_TRACE_PROPAGATION_STYLE_B3_PADDING_ENABLED` (A)

- **Mapping**: `DD_TRACE_PROPAGATION_STYLE_B3_PADDING_ENABLED` ↔ `trace.propagation.style.b3.padding.enabled` via `Config.isEnabled(true, TRACE_PROPAGATION_STYLE, \".b3.padding.enabled\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1737-1738,3365-3367`: computes/exposes `isTracePropagationStyleB3PaddingEnabled()`.
  - `dd-trace-core/src/main/java/datadog/trace/core/propagation/HttpCodec.java:109-115`: passes the padding flag into B3 injectors.
  - `dd-trace-core/src/main/java/datadog/trace/core/propagation/B3HttpCodec.java:75-104`: when padding is enabled, injects fixed-width hex IDs (32 chars for trace ID, 16 for span ID).
- **Inference**: Controls whether B3 header injection uses fixed-width hex ID padding.

### `DD_TRACE_RABBITMQ_E2E_DURATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_RABBITMQ_E2E_DURATION_ENABLED` ↔ `rabbitmq.e2e.duration.enabled` via `Config.isEndToEndDurationEnabled(..., \"rabbitmq\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5218-5222`: `.e2e.duration.enabled` toggles are resolved via `ConfigProvider.isEnabled(...)`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/MessagingClientDecorator.java:10-27`: when enabled for the instrumentation name, calls `span.beginEndToEnd()` in `afterStart`.
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitDecorator.java:265-271`: consumer spans finish with `finishWithEndToEnd()` when end-to-end durations are enabled.
- **Inference**: Enables end-to-end duration tracking on RabbitMQ/AMQP spans.

### `DD_TRACE_RABBITMQ_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_RABBITMQ_LEGACY_TRACING_ENABLED` ↔ `rabbitmq.legacy.tracing.enabled` via `Config.isLegacyTracingEnabled(true, \"rabbit\", \"rabbitmq\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5236-5240`: `*.legacy.tracing.enabled` toggles are resolved via `ConfigProvider.isEnabled(...)`.
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitDecorator.java:49-55`: legacy tracing flag is used to compute service naming and the default for time-in-queue.
- **Inference**: Controls legacy vs non-legacy naming/service selection for RabbitMQ spans (gated by inferred-services support).

### `DD_TRACE_RABBITMQ_PROPAGATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_RABBITMQ_PROPAGATION_ENABLED` ↔ `rabbitmq.propagation.enabled` via `Config.isPropagationEnabled(true, \"rabbit\", \"rabbitmq\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2656-2659,4489-4496`: reads/exposes RabbitMQ propagation enablement and per-destination disables.
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitChannelInstrumentation.java:181-211`: injects headers (and time-in-queue timestamp when enabled) only when propagation is enabled.
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/TracedDelegatingConsumer.java:30-33`: extracts/propagates context only when propagation is enabled for the queue.
- **Inference**: Enables/disables trace-context propagation through RabbitMQ AMQP message headers.

### `DD_TRACE_RABBITMQ_TIME_IN_QUEUE_ENABLED` (A)

- **Mapping**: `DD_TRACE_RABBITMQ_TIME_IN_QUEUE_ENABLED` ↔ `rabbitmq.time-in-queue.enabled` via `Config.isTimeInQueueEnabled(..., \"rabbit\", \"rabbitmq\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5267-5272`: `.time-in-queue.enabled` toggles are gated by inferred-services support.
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitDecorator.java:53-55,277-285`: time-in-queue is enabled via config and uses an injected produced timestamp header (`x_datadog_rabbitmq_produced`).
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitDecorator.java:217-236`: when a produced timestamp is present, creates an `amqp.deliver` broker span and parents the inbound span to it.
- **Inference**: Enables recording RabbitMQ broker time-in-queue using a produced timestamp header and an `amqp.deliver` span.

### `DD_TRACE_RABBIT_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_RABBIT_LEGACY_TRACING_ENABLED` ↔ `rabbit.legacy.tracing.enabled` via `Config.isLegacyTracingEnabled(true, \"rabbit\", \"rabbitmq\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5236-5240`: `*.legacy.tracing.enabled` toggles are resolved via `ConfigProvider.isEnabled(...)`.
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitDecorator.java:49-55`: legacy tracing flag is used to compute service naming and the default for time-in-queue.
- **Inference**: Controls legacy vs non-legacy naming/service selection for RabbitMQ spans (alternate integration name `rabbit`).

### `DD_TRACE_RABBIT_PROPAGATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_RABBIT_PROPAGATION_ENABLED` ↔ `rabbit.propagation.enabled` via `Config.isPropagationEnabled(true, \"rabbit\", \"rabbitmq\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2656-2659,4489-4496`: reads/exposes RabbitMQ propagation enablement and per-destination disables.
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/TracedDelegatingConsumer.java:30-33`: propagation enablement gates header extraction.
- **Inference**: Enables/disables RabbitMQ trace-context propagation (alternate integration name `rabbit`).

### `DD_TRACE_RABBIT_TIME_IN_QUEUE_ENABLED` (A)

- **Mapping**: `DD_TRACE_RABBIT_TIME_IN_QUEUE_ENABLED` ↔ `rabbit.time-in-queue.enabled` via `Config.isTimeInQueueEnabled(..., \"rabbit\", \"rabbitmq\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5267-5272`: `.time-in-queue.enabled` toggles are gated by inferred-services support.
  - `dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitDecorator.java:53-55,217-236`: creates an `amqp.deliver` span based on the injected produced timestamp header.
- **Inference**: Enables recording RabbitMQ broker time-in-queue (alternate integration name `rabbit`).

### `DD_TRACE_REJECTED_EXECUTION_HANDLER_ENABLED` (A)

- **Mapping**: `DD_TRACE_REJECTED_EXECUTION_HANDLER_ENABLED` ↔ integration toggle `rejected-execution-handler` (keys: `trace.rejected-execution-handler.enabled`, `trace.integration.rejected-execution-handler.enabled`, `integration.rejected-execution-handler.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/executor/ExecutorModule.java:71-74`: installs `RejectedExecutionHandlerInstrumentation` only when enabled.
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/executor/RejectedExecutionHandlerInstrumentation.java:69-107`: cancels wrappers / captured task state when execution is rejected.
- **Inference**: Controls instrumentation of rejected task execution to avoid leaked continuations/scopes.

### `DD_TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED` (A)

- **Mapping**: `DD_TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED` ↔ `TracerConfig.TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED` (`\"trace.remove.integration-service-names.enabled\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1615-1616,3211-3213`: reads/exposes `isRemoveIntegrationServiceNamesEnabled()` (default `false`).
  - `internal-api/src/main/java/datadog/trace/api/naming/v0/NamingSchemaV0.java:9-17`: disables inferred-services support in v0 naming schema when enabled.
- **Inference**: Disables inferred-services/integration service naming behavior in Naming Schema v0.

### `DD_TRACE_REQUEST_HEADER_TAGS_COMMA_ALLOWED` (A)

- **Mapping**: `DD_TRACE_REQUEST_HEADER_TAGS_COMMA_ALLOWED` ↔ `TracerConfig.REQUEST_HEADER_TAGS_COMMA_ALLOWED` (`\"trace.request_header.tags.comma.allowed\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1584-1586,3231-3233`: reads/exposes `isRequestHeaderTagsCommaAllowed()` (default `true`).
  - `dd-trace-core/src/main/java/datadog/trace/core/propagation/ContextInterpreter.java:193-205`: when disabled, uses only the first comma-separated header value for header tags.
  - `dd-trace-core/src/main/java/datadog/trace/core/propagation/HttpCodec.java:407-414`: `firstHeaderValue(...)` returns the value before the first comma.
- **Inference**: Controls whether tagged request header values may contain commas or are truncated to the first value.

### `DD_TRACE_RESOLVER_ENABLED` (A)

- **Mapping**: `DD_TRACE_RESOLVER_ENABLED` ↔ `TracerConfig.TRACE_RESOLVER_ENABLED` (`\"trace.resolver.enabled\"`, deprecated).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1545-1546,3179-3181`: reads/exposes `isTraceResolverEnabled()` (default `true`).
  - `dd-trace-ot/src/main/java/datadog/opentracing/resolver/DDTracerResolver.java:17-24` and `.../DDTracerFactory.java:16-24`: when enabled, creates `DDTracer`; when disabled, returns null.
- **Inference**: Controls whether OpenTracing tracer resolver/factory auto-create a `DDTracer`.

### `DD_TRACE_RESOURCENAMERULE_ENABLED` (A)

- **Mapping**: `DD_TRACE_RESOURCENAMERULE_ENABLED` ↔ rule enablement for `ResourceNameRule` via `Config.isRuleEnabled(\"ResourceNameRule\", true)`.
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/RuleFlags.java:10,46-49`: `RESOURCE_NAME` feature is enabled/disabled via `Config.isRuleEnabled(...)`.
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/TagInterceptor.java:239-251`: when enabled, the `resource.name` tag sets the span resource name.
- **Inference**: Toggle for honoring the `resource.name` tag to set the span resource name.

### `DD_TRACE_RUNNABLE_ENABLED` (A)

- **Mapping**: `DD_TRACE_RUNNABLE_ENABLED` ↔ integration toggle `runnable` (keys: `trace.runnable.enabled`, `trace.integration.runnable.enabled`, `integration.runnable.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/main/java/datadog/trace/instrumentation/java/concurrent/runnable/RunnableInstrumentation.java:35-75`: instruments `Runnable.run()` to restore/end task scope (`AdviceUtils.startTaskScope(...)` / `endTaskScope(...)`).
- **Inference**: Controls whether `Runnable` execution is instrumented for async context propagation.

### `DD_TRACE_RUNTIME_CONTEXT_FIELD_INJECTION` (A)

- **Mapping**: `DD_TRACE_RUNTIME_CONTEXT_FIELD_INJECTION` ↔ `TraceInstrumentationConfig.RUNTIME_CONTEXT_FIELD_INJECTION` (`\"trace.runtime.context.field.injection\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:329-331,609-610`: reads/exposes `isRuntimeContextFieldInjection()` (default `true`).
  - `dd-java-agent/agent-builder/src/main/java/datadog/trace/agent/tooling/CombiningTransformerBuilder.java:288-292`: when enabled, applies context-store field injection at install time.
- **Inference**: Controls whether context stores use field injection into instrumented types (field-backed context) for performance.

### `DD_TRACE_SAMPLING_MECHANISM_VALIDATION_DISABLED` (A)

- **Mapping**: `DD_TRACE_SAMPLING_MECHANISM_VALIDATION_DISABLED` ↔ `TracerConfig.SAMPLING_MECHANISM_VALIDATION_DISABLED` (`\"trace.sampling.mechanism.validation.disabled\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5364-5365`: reads the boolean (default `false`).
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:735`: stores the flag on the tracer (`disableSamplingMechanismValidation`).
  - `dd-trace-core/src/main/java/datadog/trace/core/DDSpanContext.java:599-615`: when enabled, bypasses invalid samplingMechanism+samplingPriority combination checks (instead of refusing to set).
- **Inference**: Controls whether invalid samplingMechanism/samplingPriority combinations are rejected or allowed.

### `DD_TRACE_SAMPLING_OPERATION_RULES` (A)

- **Mapping**: `DD_TRACE_SAMPLING_OPERATION_RULES` ↔ `TracerConfig.TRACE_SAMPLING_OPERATION_RULES` (`\"trace.sampling.operation.rules\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2008-2009,3548-3550`: reads/exposes the operation rules map.
  - `dd-trace-core/src/main/java/datadog/trace/common/sampling/Sampler.java:43-78`: used to build rule-based trace sampling configuration.
  - `dd-trace-core/src/main/java/datadog/trace/common/sampling/RuleBasedTraceSampler.java:90-101`: parses the operation rules map into operation sampling rules when trace sampling rules are not defined.
- **Inference**: Per-operation sampling rate overrides used by the rule-based trace sampler (deprecated in favor of `DD_TRACE_SAMPLING_RULES`).

### `DD_TRACE_SAMPLING_SERVICE_RULES` (A)

- **Mapping**: `DD_TRACE_SAMPLING_SERVICE_RULES` ↔ `TracerConfig.TRACE_SAMPLING_SERVICE_RULES` (`\"trace.sampling.service.rules\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2008-2009,3544-3546`: reads/exposes the service rules map.
  - `dd-trace-core/src/main/java/datadog/trace/common/sampling/Sampler.java:43-78`: used to build rule-based trace sampling configuration.
  - `dd-trace-core/src/main/java/datadog/trace/common/sampling/RuleBasedTraceSampler.java:76-87`: parses the service rules map into service sampling rules when trace sampling rules are not defined.
- **Inference**: Per-service sampling rate overrides used by the rule-based trace sampler (deprecated in favor of `DD_TRACE_SAMPLING_RULES`).

### `DD_TRACE_SCALA_PROMISE_COMPLETION_PRIORITY_ENABLED` (A)

- **Mapping**: `DD_TRACE_SCALA_PROMISE_COMPLETION_PRIORITY_ENABLED` ↔ integration toggle for `scala_promise_completion_priority` (`dd.trace.integration.scala_promise_completion_priority.enabled`).
- **Evidence**:
  - `dd-java-agent/instrumentation/scala/scala-promise/scala-promise-common/src/main/java/datadog/trace/instrumentation/scala/PromiseHelper.java:19-23`: reads the toggle via `InstrumenterConfig.isIntegrationEnabled(..., false)`.
  - `dd-java-agent/instrumentation/scala/scala-promise/scala-promise-2.13/src/main/java/datadog/trace/instrumentation/scala213/concurrent/PromiseTransformationInstrumentation.java:112-133`: when enabled, captures the span stored on the resolved `Try` and uses it as the task context for promise transformations.
- **Inference**: When enabled, promise callback execution prefers the completion span context (stored on the resolved `Try`) over any currently active scope.

### `DD_TRACE_SCOPE_DEPTH_LIMIT` (A)

- **Mapping**: `DD_TRACE_SCOPE_DEPTH_LIMIT` ↔ `TracerConfig.SCOPE_DEPTH_LIMIT` (`\"trace.scope.depth.limit\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1711,3323-3325`: reads/exposes the configured depth limit (default `100`).
  - `dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScopeManager.java:136-142`: when the current scope stack depth reaches the limit, activation returns a NoopScope.
  - `dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScopeManager.java:82-83`: `0` is treated as unlimited (`Integer.MAX_VALUE`).
- **Inference**: Caps nested scope activations to avoid runaway scope stacks; excess activations become no-ops.

### `DD_TRACE_SCOPE_ITERATION_KEEP_ALIVE` (A)

- **Mapping**: `DD_TRACE_SCOPE_ITERATION_KEEP_ALIVE` ↔ `TracerConfig.SCOPE_ITERATION_KEEP_ALIVE` (`\"trace.scope.iteration.keep.alive\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:80`: default is `30` (seconds).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1715-1716,3331-3333`: reads/exposes the keep-alive value.
  - `dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScopeManager.java:50-51`: converts the config value from seconds to milliseconds for iteration scope keep-alive.
  - `dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScopeManager.java:431-460`: background cleaner marks overdue root iteration scopes and finishes their spans with end-to-end semantics.
- **Inference**: Controls how long root iteration scopes (created by `AgentTracer.activateNext`) are kept alive before automatic cleanup.

### `DD_TRACE_SCOPE_STRICT_MODE` (A)

- **Mapping**: `DD_TRACE_SCOPE_STRICT_MODE` ↔ `TracerConfig.SCOPE_STRICT_MODE` (`\"trace.scope.strict.mode\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1713,3327-3329`: reads/exposes `isScopeStrictMode()` (default `false`).
  - `dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScope.java:49-60`: when enabled, closing a **manual** scope out of order throws a `RuntimeException`.
  - `dd-trace-ot/src/main/java/datadog/opentracing/OTScopeManager.java:121-127`: OpenTracing scope manager also throws (instead of warning) when strict mode is enabled and scopes are closed out of order.
- **Inference**: Makes scope-close ordering errors fail fast (exceptions) instead of only logging, improving detection of incorrect manual scope usage.

### `DD_TRACE_SECURE_RANDOM` (A)

- **Mapping**: `DD_TRACE_SECURE_RANDOM` ↔ `TracerConfig.SECURE_RANDOM` (`\"trace.secure-random\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1404-1409`: forces `secureRandom=true` on AWS Lambda SnapStart (`AWS_LAMBDA_INITIALIZATION_TYPE=snap-start`), otherwise reads the boolean from config (default `false`).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1435-1437`: when `secureRandom` is true, forces `strategyName = \"SECURE_RANDOM\"`.
  - `dd-trace-api/src/main/java/datadog/trace/api/IdGenerationStrategy.java:32-34,92-115`: `SECURE_RANDOM` selects the SecureRandom-based ID generator.
- **Inference**: Enables SecureRandom-based trace/span ID generation.

### `DD_TRACE_SERIALVERSIONUID_FIELD_INJECTION` (A)

- **Mapping**: `DD_TRACE_SERIALVERSIONUID_FIELD_INJECTION` ↔ `TraceInstrumentationConfig.SERIALVERSIONUID_FIELD_INJECTION` (`\"trace.serialversionuid.field.injection\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:332-334,613-615`: reads/exposes `isSerialVersionUIDFieldInjection()` (default `true`).
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/context/FieldBackedContextInjector.java:141-145`: when enabled and the target type is `Serializable`, prepares to inject a serialVersionUID.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/context/FieldBackedContextInjector.java:232-234`: injects the serialVersionUID at the end of transformation.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/context/FieldBackedContextInjector.java:520-534`: injects a computed `serialVersionUID` field if one is not already declared.
- **Inference**: Preserves Java serialization compatibility for Serializable classes that are modified by field-backed context injection.

### `DD_TRACE_SERVICENAMETAGINTERCEPTOR_ENABLED` (A)

- **Mapping**: `DD_TRACE_SERVICENAMETAGINTERCEPTOR_ENABLED` ↔ rule enablement for `ServiceNameTagInterceptor` via `Config.isRuleEnabled(\"ServiceNameTagInterceptor\", true)`.
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/RuleFlags.java:19,46-49`: `SERVICE_NAME` feature is enabled/disabled via `Config.isRuleEnabled(...)` (default `true`).
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/TagInterceptor.java:286-292`: when enabled, the interceptor sets the span service name from `service.name` / `service` tags and records it.
- **Inference**: Toggle for honoring service-name tags to set the span service name.

### `DD_TRACE_SERVICE_DISCOVERY_ENABLED` (A)

- **Mapping**: `DD_TRACE_SERVICE_DISCOVERY_ENABLED` ↔ `TracerConfig.TRACE_SERVICE_DISCOVERY_ENABLED` (`\"trace.service.discovery.enabled\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:265`: default is `true`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2791,3099-3101`: reads/exposes `isServiceDiscoveryEnabled()`.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/TracerInstaller.java:45-57`: when disabled (or on non-Linux/native images), service discovery is not initialized.
- **Inference**: Controls whether the tracer initializes its service discovery implementation.

### `DD_TRACE_SERVLETCONTEXTTAGINTERCEPTOR_ENABLED` (A)

- **Mapping**: `DD_TRACE_SERVLETCONTEXTTAGINTERCEPTOR_ENABLED` ↔ rule enablement for `ServletContextTagInterceptor` via `Config.isRuleEnabled(\"ServletContextTagInterceptor\", true)`.
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/RuleFlags.java:20,46-49`: `SERVLET_CONTEXT` feature is enabled/disabled via `Config.isRuleEnabled(...)` (default `true`).
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/TagInterceptor.java:329-336`: rule enablement is part of the conditions that allow servlet-context based service naming.
- **Inference**: Controls whether the servlet-context tag can be used by tag interception to affect service naming (notably when not explicitly splitting by servlet context).

### `DD_TRACE_SERVLET_ROOT_CONTEXT_SERVICE_NAME` (A)

- **Mapping**: `DD_TRACE_SERVLET_ROOT_CONTEXT_SERVICE_NAME` ↔ `TraceInstrumentationConfig.SERVLET_ROOT_CONTEXT_SERVICE_NAME` (`\"trace.servlet.root.context.service.name\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:45`: default root context service name is `root-servlet`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1390-1392`: reads `rootContextServiceName`.
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/TagInterceptor.java:341-343`: when the servlet context is `/`, the interceptor uses `Config.get().getRootContextServiceName()` for the span service name.
- **Inference**: Controls the service name used for the root servlet context when servlet-context based service naming is applied.

### `DD_TRACE_SPAN_ATTRIBUTE_SCHEMA` (B)

- **Mapping**: `DD_TRACE_SPAN_ATTRIBUTE_SCHEMA` ↔ `TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA` (`\"trace.span.attribute.schema\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5088-5104`: parses the configured version (`v0`/`v1` etc), enforces range `[SCHEMA_MIN_VERSION, SCHEMA_MAX_VERSION]`, and defaults to `v0` when invalid/out-of-range.
  - `internal-api/src/main/java/datadog/trace/api/naming/SpanNaming.java:27-38`: selects `NamingSchemaV0` vs `NamingSchemaV1` based on `Config.get().getSpanAttributeSchemaVersion()`.
- **Inference**: Selects the naming schema version used for span attribute naming decisions.

### `DD_TRACE_SPRING_MESSAGING_E2E_DURATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_SPRING_MESSAGING_E2E_DURATION_ENABLED` ↔ `spring-messaging.e2e.duration.enabled` via `Config.isEndToEndDurationEnabled(false, \"spring-messaging\")` (indirect through `MessagingClientDecorator`).
- **Evidence**:
  - `dd-java-agent/instrumentation/spring/spring-messaging-4.0/src/main/java/datadog/trace/instrumentation/springmessaging/SpringMessageDecorator.java:25-27`: instrumentation name is `spring-messaging`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/MessagingClientDecorator.java:13-26`: checks `config.isEndToEndDurationEnabled(..., instrumentationNames)` and calls `span.beginEndToEnd()` when enabled.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5218-5222`: `isEndToEndDurationEnabled` resolves `<integration>.e2e.duration.enabled` toggles.
- **Inference**: Enables end-to-end duration start tracking on spring-messaging spans.

### `DD_TRACE_SPRING_SCHEDULING_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_SPRING_SCHEDULING_LEGACY_TRACING_ENABLED` ↔ `spring-scheduling.legacy.tracing.enabled` via `Config.isLegacyTracingEnabled(false, \"spring-scheduling\")`.
- **Evidence**:
  - `dd-java-agent/instrumentation/spring/spring-scheduling-3.1/src/main/java/datadog/trace/instrumentation/springscheduling/SpringSchedulingRunnableWrapper.java:18-20`: reads legacy tracing enablement for `spring-scheduling`.
  - `dd-java-agent/instrumentation/spring/spring-scheduling-3.1/src/main/java/datadog/trace/instrumentation/springscheduling/SpringSchedulingRunnableWrapper.java:57-59`: legacy mode uses `startSpan(...)` with implicit parent; non-legacy uses `startSpan(..., null)` (explicit null parent).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5236-5240`: `isLegacyTracingEnabled` resolves `<integration>.legacy.tracing.enabled` toggles.
- **Inference**: Controls whether scheduled-task spans are linked to the currently active trace (legacy) or started as new root spans.

### `DD_TRACE_SQS_BODY_PROPAGATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_SQS_BODY_PROPAGATION_ENABLED` ↔ `TraceInstrumentationConfig.SQS_BODY_PROPAGATION_ENABLED` (`\"trace.sqs.body.propagation.enabled\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2642,4459-4461`: reads/exposes `isSqsBodyPropagationEnabled()` (default `false`).
  - `dd-java-agent/instrumentation/aws-java/aws-java-sqs-1.0/src/main/java/datadog/trace/instrumentation/aws/v1/sqs/MessageExtractAdapter.java:39-45,48-65`: when enabled and `_datadog` message attribute is absent, parses the message body JSON to extract `MessageAttributes._datadog`.
- **Inference**: Enables extracting Datadog propagation context from the message body (SNS-style payload) as a fallback to message attributes.

### `DD_TRACE_SQS_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_SQS_LEGACY_TRACING_ENABLED` ↔ `Config.isSqsLegacyTracingEnabled()` (internally: inferred-services enabled AND `sqs.legacy.tracing.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5242-5245`: legacy tracing is enabled only when inferred services are allowed.
  - `dd-java-agent/instrumentation/aws-java/aws-java-sqs-1.0/src/main/java/datadog/trace/instrumentation/aws/v1/sqs/SqsDecorator.java:24-27`: legacy tracing flag influences time-in-queue default (`!SQS_LEGACY_TRACING`) and naming schema service selection.
- **Inference**: Controls legacy tracing mode for SQS instrumentation (and the default behavior of time-in-queue tracking).

### `DD_TRACE_SQS_PROPAGATION_ENABLED` (A)

- **Mapping**: `DD_TRACE_SQS_PROPAGATION_ENABLED` ↔ `sqs.propagation.enabled` via `Config.isPropagationEnabled(true, \"sqs\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2641,4455-4457`: reads/exposes `isSqsPropagationEnabled()` (default `true`).
  - `dd-java-agent/instrumentation/aws-java/aws-java-sqs-1.0/src/main/java/datadog/trace/instrumentation/aws/v1/sqs/TracingIterator.java:64-68`: extracts message context only when propagation is enabled.
  - `dd-java-agent/instrumentation/aws-java/aws-java-sqs-1.0/src/main/java/datadog/trace/instrumentation/aws/v1/sqs/SqsReceiveRequestInstrumentation.java:34-43`: when enabled, requests `AWSTraceHeader` on receive so the attribute is available for extraction.
  - `dd-java-agent/instrumentation/aws-java/aws-java-sqs-1.0/src/main/java/datadog/trace/instrumentation/aws/v1/sqs/SqsJmsMessageInstrumentation.java:62-71`: when enabled, copies `AWSTraceHeader` into a JMS property for downstream extraction.
- **Inference**: Enables/disables distributed-context extraction for SQS consumer spans (and related request shaping to make the needed attributes available).

### `DD_TRACE_SQS_TIME_IN_QUEUE_ENABLED` (A)

- **Mapping**: `DD_TRACE_SQS_TIME_IN_QUEUE_ENABLED` ↔ `sqs.time-in-queue.enabled` via `Config.isTimeInQueueEnabled(..., \"sqs\")` (gated by inferred-services support).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5267-5272`: time-in-queue enablement requires inferred-services support.
  - `dd-java-agent/instrumentation/aws-java/aws-java-sqs-1.0/src/main/java/datadog/trace/instrumentation/aws/v1/sqs/SqsDecorator.java:25-26`: default enablement is `!SQS_LEGACY_TRACING`.
  - `dd-java-agent/instrumentation/aws-java/aws-java-sqs-1.0/src/main/java/datadog/trace/instrumentation/aws/v1/sqs/MessageExtractAdapter.java:67-76`: extracts the time-in-queue start from the message `SentTimestamp`.
  - `dd-java-agent/instrumentation/aws-java/aws-java-sqs-1.0/src/main/java/datadog/trace/instrumentation/aws/v1/sqs/TracingIterator.java:70-84`: when enabled and a `SentTimestamp` is available, creates a time-in-queue span and parents the consumer span to it.
- **Inference**: Enables SQS broker time-in-queue span creation based on the message sent timestamp.

### `DD_TRACE_STATUS404DECORATOR_ENABLED` (A)

- **Mapping**: `DD_TRACE_STATUS404DECORATOR_ENABLED` ↔ rule enablement for `Status404Decorator` via `Config.isRuleEnabled(\"Status404Decorator\", true)`.
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/RuleFlags.java:13,46-49`: `STATUS_404_DECORATOR` feature is enabled/disabled via `Config.isRuleEnabled(...)` (default `true`).
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/TagInterceptor.java:79-83`: 404 resource naming requires `URLAsResourceNameRule`, `Status404Rule`, and `Status404Decorator` to be enabled.
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/TagInterceptor.java:361-370`: when active and status is 404, sets resource name to `404`.
- **Inference**: Toggle for setting resource name to `404` for HTTP 404 responses (when the relevant URL/status rules are enabled).

### `DD_TRACE_STRICT_WRITES_ENABLED` (A)

- **Mapping**: `DD_TRACE_STRICT_WRITES_ENABLED` ↔ `TracerConfig.TRACE_STRICT_WRITES_ENABLED` (`\"trace.strict.writes.enabled\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1730,3347-3349`: reads/exposes `isTraceStrictWritesEnabled()` (default `false`).
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:506`: passes the flag into tracer construction via `strictTraceWrites(config.isTraceStrictWritesEnabled())`.
  - `dd-trace-core/src/main/java/datadog/trace/core/PendingTrace.java:275-283`: when enabled, throws if the pending reference count goes negative; writes immediately when the pending count reaches zero.
- **Inference**: Enforces strict trace-write reference accounting and early write behavior.

### `DD_TRACE_THREAD_POOL_EXECUTORS_EXCLUDE` (A)

- **Mapping**: `DD_TRACE_THREAD_POOL_EXECUTORS_EXCLUDE` ↔ `TraceInstrumentationConfig.TRACE_THREAD_POOL_EXECUTORS_EXCLUDE` (`\"trace.thread-pool-executors.exclude\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:284-285,481-483`: reads/exposes the exclusion set from the config list.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/java/concurrent/TPEHelper.java:59-64`: disables propagation for executors whose class name matches an excluded entry.
- **Inference**: Allows opting out specific `ThreadPoolExecutor` implementations from thread-pool-executors context propagation/wrapping.

### `DD_TRACE_THREAD_POOL_EXECUTORS_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_THREAD_POOL_EXECUTORS_LEGACY_TRACING_ENABLED` ↔ `trace.thread-pool-executors.legacy.tracing.enabled` via `InstrumenterConfig.isLegacyInstrumentationEnabled(..., \"trace.thread-pool-executors\")`.
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:659-663`: legacy instrumentation toggles resolve `<integration>.legacy.tracing.enabled`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/java/concurrent/TPEHelper.java:14-17,41-47`: legacy mode propagates via wrapping; non-legacy uses field-backed context (and a ThreadLocal between executor hooks).
  - `dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/test/groovy/executor/ExecutorInstrumentationTest.groovy:488-495`: tests toggle `dd.trace.thread-pool-executors.legacy.tracing.enabled`.
- **Inference**: Controls whether thread-pool-executors uses legacy wrapping-based propagation vs field-backed context propagation.

### `DD_TRACE_TRACER_METRICS_BUFFERING_ENABLED` (A)

- **Mapping**: `DD_TRACE_TRACER_METRICS_BUFFERING_ENABLED` ↔ `GeneralConfig.TRACER_METRICS_BUFFERING_ENABLED` (`\"trace.tracer.metrics.buffering.enabled\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1980-1981,3502-3504`: reads/exposes `isTracerMetricsBufferingEnabled()` (default `false`).
  - `dd-trace-core/src/main/java/datadog/trace/common/metrics/ConflatingMetricsAggregator.java:115-123`: passes `config.isTracerMetricsBufferingEnabled()` into `OkHttpSink`.
  - `dd-trace-core/src/main/java/datadog/trace/common/metrics/OkHttpSink.java:69-91`: when buffering is enabled and request latency is high, requests are copied/queued and sent asynchronously.
- **Inference**: Controls whether tracer metrics payloads can be buffered and sent asynchronously under agent slowness.

### `DD_TRACE_TRACER_METRICS_ENABLED` (A)

- **Mapping**: `DD_TRACE_TRACER_METRICS_ENABLED` ↔ `GeneralConfig.TRACER_METRICS_ENABLED` (`\"trace.tracer.metrics.enabled\"`) (also consulted via legacy key `trace.stats.computation.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1978-1979`: reads `tracerMetricsEnabled` (prefers `TRACE_STATS_COMPUTATION_ENABLED`, falls back to `TRACER_METRICS_ENABLED`).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3497-3500`: `isTracerMetricsEnabled()` is additionally gated by `isApmTracingEnabled()`.
  - `dd-trace-core/src/main/java/datadog/trace/common/metrics/MetricsAggregatorFactory.java:16-22`: creates a metrics aggregator only when tracer metrics are enabled.
  - `dd-trace-core/src/main/java/datadog/trace/common/writer/WriterFactory.java:144-151`: passes `config.isTracerMetricsEnabled()` into `DDAgentApi` (metrics reporting behavior).
- **Inference**: Enables computing and reporting tracer metrics (client stats) when tracing is enabled.

### `DD_TRACE_TRACER_METRICS_IGNORED_RESOURCES` (A)

- **Mapping**: `DD_TRACE_TRACER_METRICS_IGNORED_RESOURCES` ↔ `GeneralConfig.TRACER_METRICS_IGNORED_RESOURCES` (`\"trace.tracer.metrics.ignored.resources\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:4839-4841`: reads the ignored resources list.
  - `dd-trace-core/src/main/java/datadog/trace/common/metrics/ConflatingMetricsAggregator.java:280-286`: if a span’s resource name is in `ignoredResources`, skips publishing tracer metrics for the trace.
- **Inference**: Allows excluding specific resource names from tracer metrics computation.

### `DD_TRACE_TRACER_METRICS_MAX_AGGREGATES` (A)

- **Mapping**: `DD_TRACE_TRACER_METRICS_MAX_AGGREGATES` ↔ `GeneralConfig.TRACER_METRICS_MAX_AGGREGATES` (`\"trace.tracer.metrics.max.aggregates\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1982-1983,3506-3508`: reads/exposes the configured max aggregates (default `2048`).
  - `dd-trace-core/src/main/java/datadog/trace/common/metrics/ConflatingMetricsAggregator.java:179-183`: uses `maxAggregates` to size internal pools/maps for metric aggregation.
- **Inference**: Caps the number of metric aggregates the tracer metrics aggregator keeps in memory.

### `DD_TRACE_TRACER_METRICS_MAX_PENDING` (A)

- **Mapping**: `DD_TRACE_TRACER_METRICS_MAX_PENDING` ↔ `GeneralConfig.TRACER_METRICS_MAX_PENDING` (`\"trace.tracer.metrics.max.pending\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1982-1983,3510-3512`: reads/exposes the configured max pending size (default `2048`).
  - `dd-trace-core/src/main/java/datadog/trace/common/metrics/ConflatingMetricsAggregator.java:179-181`: uses `queueSize` to size the internal inbox queue for pending metric events.
- **Inference**: Controls the buffer size for pending tracer metrics events.

### `DD_TRACE_TRIAGE` (A)

- **Mapping**: `DD_TRACE_TRIAGE` ↔ `GeneralConfig.TRACE_TRIAGE` (`\"trace.triage\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:228-234`: setting `TRIAGE_REPORT_TRIGGER` implies triage mode; otherwise triage defaults to the debug state unless overridden.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2720-2726`: reads/exposes `triageEnabled`, plus optional triage report trigger/dir settings.
  - `dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterMetrics.java:36-44`: enables recording instrumenter metrics only when triage is enabled.
  - `utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java:151-153`: uses triage mode (or debug) to decide whether to include thread dumps in tracer flares.
- **Inference**: Enables additional diagnostics/metrics aimed at troubleshooting and affects tracer flare content.

### `DD_TRACE_UNDERTOW_LEGACY_TRACING_ENABLED` (A)

- **Mapping**: `DD_TRACE_UNDERTOW_LEGACY_TRACING_ENABLED` ↔ `undertow.legacy.tracing.enabled` via `Config.isLegacyTracingEnabled(true, \"undertow\")`.
- **Evidence**:
  - `dd-java-agent/instrumentation/undertow/undertow-common/src/main/java/datadog/trace/instrumentation/undertow/UndertowDecorator.java:36-37`: reads legacy tracing enablement for Undertow.
  - `dd-java-agent/instrumentation/undertow/undertow-2.0/src/main/java/datadog/trace/instrumentation/undertow/ServletInstrumentation.java:78-87`: when enabled, sets the HTTP route on dispatch for non-default servlet mapping matches.
- **Inference**: Controls whether Undertow instrumentation uses legacy tracing behavior for route/resource naming.

### `DD_TRACE_URLASRESOURCENAMERULE_ENABLED` (A)

- **Mapping**: `DD_TRACE_URLASRESOURCENAMERULE_ENABLED` ↔ rule enablement for `URLAsResourceNameRule` via `Config.isRuleEnabled(\"URLAsResourceNameRule\", true)`.
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/RuleFlags.java:11,46-49`: `URL_AS_RESOURCE_NAME` feature is enabled/disabled via `Config.isRuleEnabled(...)`.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/http/HttpResourceDecorator.java:16-32`: when disabled, `withServerPath(...)` sets the default resource name `/` instead of using the URL/path.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpServerDecorator.java:71-77`: URLAsResourceNameRule participates in `SHOULD_SET_404_RESOURCE_NAME` and URL resource naming behavior.
- **Inference**: Toggle for using URL/path-based resource naming for HTTP server spans (and related 404 resource naming behavior).

### `DD_TRIAGE_REPORT_DIR` (B)

- **Mapping**: `DD_TRIAGE_REPORT_DIR` ↔ `GeneralConfig.TRIAGE_REPORT_DIR` (`\"triage.report.dir\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2721-2725,4571-4573`: reads/exposes the triage report directory only when a trigger is set (default: `java.io.tmpdir`).
  - `utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java:83-96`: creates the directory and writes the triage report zip file there.
- **Inference**: Controls where scheduled triage report archives are written.

### `DD_TRIAGE_REPORT_TRIGGER` (A)

- **Mapping**: `DD_TRIAGE_REPORT_TRIGGER` ↔ `GeneralConfig.TRIAGE_REPORT_TRIGGER` (`\"triage.report.trigger\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:228-234`: presence of a trigger implies triage mode.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2721-2723,4567-4569`: reads/exposes the trigger.
  - `utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java:71-103`: parses the delay (`TimeUtils.parseSimpleDelay`) and schedules triage report collection.
- **Inference**: Schedules automatic triage report generation after a configured delay.

### `DD_USM_ENABLED` (A)

- **Mapping**: `DD_USM_ENABLED` ↔ `UsmConfig.USM_ENABLED` (`\"usm.enabled\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:266-268,461-462`: reads/exposes `isUsmEnabled()` (default `false`).
  - `dd-java-agent/agent-builder/src/main/java/datadog/trace/agent/tooling/AgentInstaller.java:319-321`: enables the `USM` target system when configured.
  - `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java:127,268`: uses `usm.enabled` to enable the USM feature.
- **Inference**: Controls whether USM features/instrumentations are enabled in the Java tracer agent.

### `DD_WRITER_BAGGAGE_INJECT` (A)

- **Mapping**: `DD_WRITER_BAGGAGE_INJECT` ↔ `TracerConfig.WRITER_BAGGAGE_INJECT` (`\"writer.baggage.inject\"`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/Config.java:1402-1403,3131-3133`: reads/exposes `isInjectBaggageAsTagsEnabled()` (default `true`).
  - `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java:506-508`: passes the flag into tracer construction (`injectBaggageAsTags`).
  - `dd-trace-core/src/main/java/datadog/trace/core/DDSpanContext.java:1075-1083`: when enabled, serializes baggage items as tags/metadata (combined with propagation tags); when disabled, only propagation tags are serialized.
- **Inference**: Controls whether baggage items are included as tags/metadata on spans when they are serialized for export.

### `OTEL_TRACES_SAMPLER` (C)

- **Mapping**: `OTEL_TRACES_SAMPLER` ↔ OpenTelemetry property `otel.traces.sampler` (sysprop/env var/config file), mapped into Datadog `DD_TRACE_SAMPLE_RATE` by `OtelEnvironmentConfigSource`.
- **Evidence**:
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/OtelEnvironmentConfigSource.java:127-134`: reads `otel.traces.sampler` and maps it to a Datadog sample rate via `mapSampleRate(...)`.
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/OtelEnvironmentConfigSource.java:401-422`: supports parent-based samplers and maps them to a Datadog sample rate (unsupported values are ignored with a warning).
- **Inference**: Selects the OpenTelemetry sampler used for mapping into Datadog trace sample rate configuration.

### `OTEL_TRACES_SAMPLER_ARG` (C)

- **Mapping**: `OTEL_TRACES_SAMPLER_ARG` ↔ OpenTelemetry property `otel.traces.sampler.arg` (sysprop/env var/config file).
- **Evidence**:
  - `utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/OtelEnvironmentConfigSource.java:411-416`: when sampler is `parentbased_traceidratio`, returns `otel.traces.sampler.arg` as the mapped sample rate; always_on/off map to `1.0`/`0.0`.
- **Inference**: Provides the sampling argument (ratio) used when the selected OpenTelemetry sampler is `parentbased_traceidratio`.

### `DD_APPSEC_REPORTING_INBAND` (A)

- **Mapping**: `DD_APPSEC_REPORTING_INBAND` ↔ `AppSecConfig.APPSEC_REPORTING_INBAND` (`\"appsec.reporting.inband\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/AppSecConfig.java:7`: defines `APPSEC_REPORTING_INBAND`.
  - `dd-trace-api/src/main/java/datadog/trace/api/ConfigDefaults.java:128`: default is `false`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2189-2190,3763-3765`: reads/exposes `isAppSecReportingInband()`.
- **Inference**: The setting is read into `Config`, but no runtime usage sites were found beyond the getter (so it currently has no effect in this repository).

### `DD_APPSEC_REPORT_TIMEOUT` (A)

- **Mapping**: `DD_APPSEC_REPORT_TIMEOUT` ↔ `AppSecConfig.APPSEC_REPORT_TIMEOUT_SEC` (`\"appsec.report.timeout\"`).
- **Evidence**:
  - `dd-trace-api/src/main/java/datadog/trace/api/config/AppSecConfig.java:9`: defines `APPSEC_REPORT_TIMEOUT_SEC`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:2194-2195`: reads max timeout (default `60`) and derives a min timeout (`min(max, 5)`).
  - `internal-api/src/main/java/datadog/trace/api/Config.java:3767-3773`: exposes the min/max timeout getters.
- **Inference**: The setting is read into `Config` but no runtime usage sites were found beyond the getters (so it currently has no effect in this repository).

### `DD_TRACE_AXIS2_TRANSPORT_ENABLED` (A)

- **Mapping (expected)**: integration toggle for `axis2-transport` (would map to `trace.axis2-transport.enabled` / `trace.integration.axis2-transport.enabled` / `integration.axis2-transport.enabled`).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:380-408`: integration enablement is driven by integration names (config keys `trace.<name>.enabled`, etc).
  - `dd-java-agent/instrumentation/axis2-1.3/src/main/java/datadog/trace/instrumentation/axis2/Axis2Module.java:11-14`: Axis2 instrumentation is registered under the integration/module name `axis2`.
  - `dd-java-agent/instrumentation/axis2-1.3/src/main/java/datadog/trace/instrumentation/axis2/Axis2Module.java:24-29`: `AxisTransportInstrumentation` is always included as part of the `axis2` module.
- **Inference**: There is no separate `axis2-transport` module/integration toggle in the codebase; transport instrumentation is installed under `axis2`, so this key is not consulted in this repository.

### `DD_TRACE_FILEITEMITERATOR_ENABLED` (A)

- **Mapping (expected)**: integration toggle for `fileitemiterator` (would map to `trace.fileitemiterator.enabled`, etc).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:380-408`: integration enablement is driven by integration names (config keys `trace.<name>.enabled`, etc).
  - `dd-java-agent/instrumentation/commons-fileupload-1.5/src/main/java/datadog/trace/instrumentation/commons/fileupload/CommonsFileUploadModule.java:11-14`: commons-fileupload instrumentation is registered under the integration/module name `commons-fileupload`.
  - `dd-java-agent/instrumentation/commons-fileupload-1.5/src/main/java/datadog/trace/instrumentation/commons/fileupload/CommonsFileUploadModule.java:18-23`: `FileItemIteratorInstrumentation` is installed as part of the `commons-fileupload` module.
  - `dd-java-agent/instrumentation/commons-fileupload-1.5/src/main/java/datadog/trace/instrumentation/commons/fileupload/FileItemIteratorInstrumentation.java:41-52`: taints the returned `FileItemStream` if the iterator is tainted (IAST propagation).
- **Inference**: No integration/module named `fileitemiterator` is used; this instrumentation is controlled by the `commons-fileupload` module + IAST enablement, so this key is not consulted in this repository.

### `DD_TRACE_FILEITEMSTREAM_ENABLED` (A)

- **Mapping (expected)**: integration toggle for `fileitemstream` (would map to `trace.fileitemstream.enabled`, etc).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:380-408`: integration enablement is driven by integration names.
  - `dd-java-agent/instrumentation/commons-fileupload-1.5/src/main/java/datadog/trace/instrumentation/commons/fileupload/CommonsFileUploadModule.java:18-23`: `FileItemStreamInstrumentation` is installed as part of the `commons-fileupload` module.
  - `dd-java-agent/instrumentation/commons-fileupload-1.5/src/main/java/datadog/trace/instrumentation/commons/fileupload/FileItemStreamInstrumentation.java:42-53`: taints the returned `InputStream` if the `FileItemStream` is tainted (IAST propagation).
- **Inference**: No integration/module named `fileitemstream` is used; this instrumentation is controlled by the `commons-fileupload` module + IAST enablement, so this key is not consulted in this repository.

### `DD_TRACE_FILEITEM_ENABLED` (A)

- **Mapping (expected)**: integration toggle for `fileitem` (would map to `trace.fileitem.enabled`, etc).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:380-408`: integration enablement is driven by integration names.
  - `dd-java-agent/instrumentation/commons-fileupload-1.5/src/main/java/datadog/trace/instrumentation/commons/fileupload/CommonsFileUploadModule.java:18-23`: `FileItemInstrumentation` is installed as part of the `commons-fileupload` module.
  - `dd-java-agent/instrumentation/commons-fileupload-1.5/src/main/java/datadog/trace/instrumentation/commons/fileupload/FileItemInstrumentation.java:42-53`: taints the returned `InputStream` if the `FileItem` is tainted (IAST propagation).
- **Inference**: No integration/module named `fileitem` is used; this instrumentation is controlled by the `commons-fileupload` module + IAST enablement, so this key is not consulted in this repository.

### `DD_TRACE_FORCEMANUALKEEPTAGINTERCEPTOR_ENABLED` (A)

- **Mapping**: `DD_TRACE_FORCEMANUALKEEPTAGINTERCEPTOR_ENABLED` ↔ rule enablement for `ForceManualKeepTagInterceptor` via `Config.isRuleEnabled(\"ForceManualKeepTagInterceptor\", true)` (reads `trace.ForceManualKeepTagInterceptor.enabled` and a lowercase variant).
- **Evidence**:
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/RuleFlags.java:15-17,43-50`: `FORCE_MANUAL_KEEP` exists as a rule flag and is populated via `Config.isRuleEnabled(...)`.
  - `internal-api/src/main/java/datadog/trace/api/Config.java:5198-5203`: how rule enablement is read (`trace.<RuleName>.enabled` + lowercase variant).
  - `dd-trace-core/src/main/java/datadog/trace/core/taginterceptor/TagInterceptor.java:143-147`: `manual.keep` is always honored (calls `span.forceKeep()` directly) without consulting any rule flag.
- **Inference**: The rule flag exists, but there is no runtime check that uses it for manual keep handling, so toggling this setting has no effect in this repository.

### `DD_TRACE_GOOGLE_PUBSUB_PUBLISHER_ENABLED` (A)

- **Mapping (expected)**: integration toggle for `google-pubsub-publisher` (would map to `trace.google-pubsub-publisher.enabled`, etc).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:380-408`: integration enablement is driven by integration names.
  - `dd-java-agent/instrumentation/google-pubsub-1.116/src/main/java/datadog/trace/instrumentation/googlepubsub/GooglePubSubModule.java:20-22`: Pub/Sub instrumentation is registered under the integration/module name `google-pubsub`.
  - `dd-java-agent/instrumentation/google-pubsub-1.116/src/main/java/datadog/trace/instrumentation/googlepubsub/GooglePubSubModule.java:42-46`: publisher instrumentation is always installed as part of that module.
- **Inference**: There is no separate `google-pubsub-publisher` module/integration toggle; publisher instrumentation is installed under `google-pubsub`, so this key is not consulted in this repository.

### `DD_TRACE_GOOGLE_PUBSUB_RECEIVER_ENABLED` (A)

- **Mapping (expected)**: integration toggle for `google-pubsub-receiver` (would map to `trace.google-pubsub-receiver.enabled`, etc).
- **Evidence**:
  - `dd-java-agent/instrumentation/google-pubsub-1.116/src/main/java/datadog/trace/instrumentation/googlepubsub/GooglePubSubModule.java:42-46`: receiver instrumentations are always installed as part of the `google-pubsub` module.
- **Inference**: There is no separate `google-pubsub-receiver` module/integration toggle; receiver instrumentation is installed under `google-pubsub`, so this key is not consulted in this repository.

### `DD_TRACE_HTTPCLIENT_REDIRECT_ENABLED` (A)

- **Mapping (expected)**: integration toggle for `httpclient-redirect` / `httpclient.redirect` (would map to `trace.httpclient-redirect.enabled` or `trace.httpclient.redirect.enabled`, etc).
- **Evidence**:
  - `internal-api/src/main/java/datadog/trace/api/InstrumenterConfig.java:380-408`: integration enablement is driven by integration names.
  - `dd-java-agent/instrumentation/apache-httpclient/apache-httpasyncclient-4.0/src/main/java/datadog/trace/instrumentation/apachehttpasyncclient/ApacheHttpAsyncClientModule.java:14-16`: module is registered under `httpasyncclient` (and alias `apache-httpasyncclient`).
  - `dd-java-agent/instrumentation/apache-httpclient/apache-httpasyncclient-4.0/src/main/java/datadog/trace/instrumentation/apachehttpasyncclient/ApacheHttpAsyncClientModule.java:36-40`: `ApacheHttpClientRedirectInstrumentation` is always installed as part of that module.
  - `dd-java-agent/instrumentation/apache-httpclient/apache-httpasyncclient-4.0/src/main/java/datadog/trace/instrumentation/apachehttpasyncclient/ApacheHttpClientRedirectInstrumentation.java:20-24,61-85`: copies propagation headers from the original request to the redirect request when necessary.
- **Inference**: There is no separate redirect-only module/integration toggle; redirect instrumentation is installed under the `httpasyncclient` module, so this key is not consulted in this repository.

