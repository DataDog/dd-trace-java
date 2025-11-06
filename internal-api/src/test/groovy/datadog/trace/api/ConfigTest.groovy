package datadog.trace.api

import datadog.trace.api.env.FixedCapturedEnvironment
import datadog.trace.bootstrap.config.provider.AgentArgsInjector
import datadog.trace.bootstrap.config.provider.ConfigConverter
import datadog.trace.bootstrap.config.provider.ConfigProvider
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.throwable.FatalAgentMisconfigurationError
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_OTEL_ENABLED
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_ERROR_STATUSES
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ERROR_STATUSES
import static datadog.trace.api.ConfigDefaults.DEFAULT_PARTIAL_FLUSH_MIN_SPANS
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL
import static datadog.trace.api.DDTags.HOST_TAG
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG
import static datadog.trace.api.DDTags.RUNTIME_VERSION_TAG
import static datadog.trace.api.DDTags.SERVICE
import static datadog.trace.api.DDTags.SERVICE_TAG
import static datadog.trace.api.TracePropagationStyle.B3MULTI
import static datadog.trace.api.TracePropagationStyle.B3SINGLE
import static datadog.trace.api.TracePropagationStyle.DATADOG
import static datadog.trace.api.TracePropagationStyle.HAYSTACK
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT
import static datadog.trace.api.TracePropagationStyle.BAGGAGE
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ENABLED
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_ENABLED
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_EXCLUDE_FILES
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_INSTRUMENT_THE_WORLD
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_METRICS_ENABLED
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_POLL_INTERVAL
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_PROBE_FILE
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_SNAPSHOT_URL
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_INTERVAL_SECONDS
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE
import static datadog.trace.api.config.DebuggerConfig.EXCEPTION_REPLAY_ENABLED
import static datadog.trace.api.config.GeneralConfig.API_KEY
import static datadog.trace.api.config.GeneralConfig.API_KEY_FILE
import static datadog.trace.api.config.GeneralConfig.CONFIGURATION_FILE
import static datadog.trace.api.config.GeneralConfig.ENV
import static datadog.trace.api.config.GeneralConfig.GLOBAL_TAGS
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_HOST
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_PORT
import static datadog.trace.api.config.GeneralConfig.JDK_SOCKET_ENABLED
import static datadog.trace.api.config.GeneralConfig.PERF_METRICS_ENABLED
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME
import static datadog.trace.api.config.GeneralConfig.SITE
import static datadog.trace.api.config.GeneralConfig.TAGS
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_IGNORED_RESOURCES
import static datadog.trace.api.config.GeneralConfig.VERSION
import static datadog.trace.api.config.GeneralConfig.SSI_INJECTION_ENABLED
import static datadog.trace.api.config.GeneralConfig.SSI_INJECTION_FORCE
import static datadog.trace.api.config.GeneralConfig.INSTRUMENTATION_SOURCE
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CHECK_PERIOD
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_ENABLED
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_METRICS_CONFIGS
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_REFRESH_BEANS_PERIOD
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_HOST
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_PORT
import static datadog.trace.api.config.JmxFetchConfig.JMX_TAGS
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_AGENTLESS_ENABLED
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_ML_APP
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_ENABLED
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AGENTLESS
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_OLD
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_VERY_OLD
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_HOST
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PASSWORD
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PORT
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_USERNAME
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_DELAY
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_DELAY_DEFAULT
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST_DEFAULT
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TAGS
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_COMPRESSION
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_PERIOD
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_TIMEOUT
import static datadog.trace.api.config.ProfilingConfig.PROFILING_URL
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_MAX_PAYLOAD_SIZE
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_POLL_INTERVAL_SECONDS
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_URL
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_HOST
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN
import static datadog.trace.api.config.TraceInstrumentationConfig.RUNTIME_CONTEXT_FIELD_INJECTION
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED
import static datadog.trace.api.config.TracerConfig.AGENT_HOST
import static datadog.trace.api.config.TracerConfig.AGENT_PORT_LEGACY
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET
import static datadog.trace.api.config.TracerConfig.BAGGAGE_MAPPING
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.HTTP_CLIENT_ERROR_STATUSES
import static datadog.trace.api.config.TracerConfig.HTTP_SERVER_ERROR_STATUSES
import static datadog.trace.api.config.TracerConfig.ID_GENERATION_STRATEGY
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_ENABLED
import static datadog.trace.api.config.TracerConfig.TRACE_EXPERIMENTAL_FEATURES_ENABLED
import static datadog.trace.api.config.TracerConfig.TRACE_LONG_RUNNING_ENABLED
import static datadog.trace.api.config.TracerConfig.TRACE_LONG_RUNNING_FLUSH_INTERVAL
import static datadog.trace.api.config.TracerConfig.TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_MIN_SPANS
import static datadog.trace.api.config.TracerConfig.PRIORITIZATION_TYPE
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING
import static datadog.trace.api.config.TracerConfig.PROPAGATION_STYLE_EXTRACT
import static datadog.trace.api.config.TracerConfig.PROPAGATION_STYLE_INJECT
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.RESPONSE_HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING
import static datadog.trace.api.config.TracerConfig.SPAN_TAGS
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PORT
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_URL
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_EXTRACT_FIRST
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_BEHAVIOR_EXTRACT
import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT
import static datadog.trace.api.config.TracerConfig.TRACE_REPORT_HOSTNAME
import static datadog.trace.api.config.TracerConfig.TRACE_RESOLVER_ENABLED
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_OPERATION_RULES
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES
import static datadog.trace.api.config.TracerConfig.TRACE_X_DATADOG_TAGS_MAX_LENGTH
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE
import static datadog.trace.api.config.OtlpConfig.Protocol.GRPC
import static datadog.trace.api.config.OtlpConfig.Protocol.HTTP_PROTOBUF
import static datadog.trace.api.config.OtlpConfig.Protocol.HTTP_JSON
import static datadog.trace.api.config.OtlpConfig.Temporality.CUMULATIVE
import static datadog.trace.api.config.OtlpConfig.Temporality.DELTA
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_ENABLED
import static datadog.trace.api.config.OtlpConfig.OTLP_ENDPOINT
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_INTERVAL
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_TIMEOUT
import static datadog.trace.api.config.OtlpConfig.OTLP_PROTOCOL
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_ENDPOINT
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_HEADERS
import static datadog.trace.api.config.OtlpConfig.OTLP_HEADERS
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_PROTOCOL
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_TIMEOUT
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_TEMPORALITY_PREFERENCE
import static datadog.trace.api.config.OtlpConfig.OTLP_TIMEOUT
import datadog.trace.config.inversion.ConfigHelper

class ConfigTest extends DDSpecification {
  private static final String PREFIX = "dd."
  private static final DD_API_KEY_ENV = "DD_API_KEY"
  private static final DD_SERVICE_NAME_ENV = "DD_SERVICE_NAME"
  private static final DD_TRACE_ENABLED_ENV = "DD_TRACE_ENABLED"
  private static final DD_WRITER_TYPE_ENV = "DD_WRITER_TYPE"
  private static final DD_PRIORITIZATION_TYPE_ENV = "DD_PRIORITIZATION_TYPE"
  private static final DD_SERVICE_MAPPING_ENV = "DD_SERVICE_MAPPING"
  private static final DD_TAGS_ENV = "DD_TAGS"
  private static final DD_ENV_ENV = "DD_ENV"
  private static final DD_VERSION_ENV = "DD_VERSION"
  private static final DD_GLOBAL_TAGS_ENV = "DD_TRACE_GLOBAL_TAGS"
  private static final DD_SPAN_TAGS_ENV = "DD_TRACE_SPAN_TAGS"
  private static final DD_HEADER_TAGS_ENV = "DD_TRACE_HEADER_TAGS"
  private static final DD_JMX_TAGS_ENV = "DD_TRACE_JMX_TAGS"
  private static final DD_PROPAGATION_STYLE_EXTRACT = "DD_PROPAGATION_STYLE_EXTRACT"
  private static final DD_PROPAGATION_STYLE_INJECT = "DD_PROPAGATION_STYLE_INJECT"
  private static final DD_TRACE_PROPAGATION_EXTRACT_FIRST = "DD_TRACE_PROPAGATION_EXTRACT_FIRST"
  private static final DD_JMXFETCH_METRICS_CONFIGS_ENV = "DD_JMXFETCH_METRICS_CONFIGS"
  private static final DD_TRACE_AGENT_PORT_ENV = "DD_TRACE_AGENT_PORT"
  private static final DD_AGENT_PORT_LEGACY_ENV = "DD_AGENT_PORT"
  private static final DD_TRACE_HEADER_TAGS = "DD_TRACE_HEADER_TAGS"
  private static final DD_TRACE_REPORT_HOSTNAME = "DD_TRACE_REPORT_HOSTNAME"
  private static final DD_RUNTIME_METRICS_ENABLED_ENV = "DD_RUNTIME_METRICS_ENABLED"
  private static final DD_TRACE_LONG_RUNNING_ENABLED = "DD_TRACE_EXPERIMENTAL_LONG_RUNNING_ENABLED"
  private static final DD_TRACE_LONG_RUNNING_FLUSH_INTERVAL = "DD_TRACE_EXPERIMENTAL_LONG_RUNNING_FLUSH_INTERVAL"
  private static final DD_PROFILING_API_KEY_OLD_ENV = "DD_PROFILING_API_KEY"
  private static final DD_PROFILING_API_KEY_VERY_OLD_ENV = "DD_PROFILING_APIKEY"
  private static final DD_PROFILING_TAGS_ENV = "DD_PROFILING_TAGS"
  private static final DD_PROFILING_PROXY_PASSWORD_ENV = "DD_PROFILING_PROXY_PASSWORD"
  private static final DD_TRACE_X_DATADOG_TAGS_MAX_LENGTH = "DD_TRACE_X_DATADOG_TAGS_MAX_LENGTH"
  private static final DD_LLMOBS_ENABLED_ENV = "DD_LLMOBS_ENABLED"
  private static final DD_LLMOBS_ML_APP_ENV = "DD_LLMOBS_ML_APP"
  private static final DD_LLMOBS_AGENTLESS_ENABLED_ENV = "DD_LLMOBS_AGENTLESS_ENABLED"

  private static final DD_METRICS_OTEL_ENABLED_ENV = "DD_METRICS_OTEL_ENABLED"
  private static final DD_METRICS_OTEL_ENABLED_PROP = "dd.metrics.otel.enabled"

  private static final OTEL_RESOURCE_ATTRIBUTES_ENV = "OTEL_RESOURCE_ATTRIBUTES"
  private static final OTEL_RESOURCE_ATTRIBUTES_PROP = "otel.resource.attributes"

  private static final OTEL_METRIC_EXPORT_TIMEOUT_ENV = "OTEL_METRIC_EXPORT_TIMEOUT"
  private static final OTEL_METRIC_EXPORT_TIMEOUT_PROP = "otel.metric.export.timeout"
  private static final OTEL_METRIC_EXPORT_INTERVAL_ENV = "OTEL_METRIC_EXPORT_INTERVAL"
  private static final OTEL_METRIC_EXPORT_INTERVAL_PROP = "otel.metric.export.interval"

  private static final OTEL_EXPORTER_OTLP_ENDPOINT_ENV = "OTEL_EXPORTER_OTLP_ENDPOINT"
  private static final OTEL_EXPORTER_OTLP_ENDPOINT_PROP = "otel.exporter.otlp.endpoint"
  private static final OTEL_EXPORTER_OTLP_HEADERS_ENV = "OTEL_EXPORTER_OTLP_HEADERS"
  private static final OTEL_EXPORTER_OTLP_HEADERS_PROP = "otel.exporter.otlp.headers"
  private static final OTEL_EXPORTER_OTLP_PROTOCOL_ENV = "OTEL_EXPORTER_OTLP_PROTOCOL"
  private static final OTEL_EXPORTER_OTLP_PROTOCOL_PROP = "otel.exporter.otlp.protocol"
  private static final OTEL_EXPORTER_OTLP_TIMEOUT_ENV = "OTEL_EXPORTER_OTLP_TIMEOUT"
  private static final OTEL_EXPORTER_OTLP_TIMEOUT_PROP = "otel.exporter.otlp.timeout"

  private static final OTEL_EXPORTER_OTLP_METRICS_ENDPOINT_ENV = "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT"
  private static final OTEL_EXPORTER_OTLP_METRICS_ENDPOINT_PROP = "otel.exporter.otlp.metrics.endpoint"
  private static final OTEL_EXPORTER_OTLP_METRICS_HEADERS_ENV = "OTEL_EXPORTER_OTLP_METRICS_HEADERS"
  private static final OTEL_EXPORTER_OTLP_METRICS_HEADERS_PROP = "otel.exporter.otlp.metrics.headers"
  private static final OTEL_EXPORTER_OTLP_METRICS_PROTOCOL_ENV = "OTEL_EXPORTER_OTLP_METRICS_PROTOCOL"
  private static final OTEL_EXPORTER_OTLP_METRICS_PROTOCOL_PROP = "otel.exporter.otlp.metrics.protocol"
  private static final OTEL_EXPORTER_OTLP_METRICS_TIMEOUT_ENV = "OTEL_EXPORTER_OTLP_METRICS_TIMEOUT"
  private static final OTEL_EXPORTER_OTLP_METRICS_TIMEOUT_PROP = "otel.exporter.otlp.metrics.timeout"

  private static final OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE_ENV = "OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE"
  private static final OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE_PROP = "otel.exporter.otlp.metrics.temporality.preference"

  def setup() {
    FixedCapturedEnvironment.useFixedEnv([:])
  }

  def "specify overrides via properties"() {
    setup:
    def prop = new Properties()
    prop.setProperty(API_KEY, "new api key")
    prop.setProperty(SITE, "new site")
    prop.setProperty(SERVICE_NAME, "something else")
    prop.setProperty(TRACE_ENABLED, "false")
    prop.setProperty(ID_GENERATION_STRATEGY, "SEQUENTIAL")
    prop.setProperty(WRITER_TYPE, "LoggingWriter")
    prop.setProperty(AGENT_HOST, "somehost")
    prop.setProperty(TRACE_AGENT_PORT, "123")
    prop.setProperty(AGENT_UNIX_DOMAIN_SOCKET, "somepath")
    prop.setProperty(AGENT_PORT_LEGACY, "456")
    prop.setProperty(PRIORITY_SAMPLING, "false")
    prop.setProperty(TRACE_RESOLVER_ENABLED, "false")
    prop.setProperty(SERVICE_MAPPING, "a:1:2")
    prop.setProperty(GLOBAL_TAGS, "b:2")
    prop.setProperty(SPAN_TAGS, "c:3")
    prop.setProperty(JMX_TAGS, "d:4")
    prop.setProperty(HEADER_TAGS, "e:five")
    prop.setProperty(BAGGAGE_MAPPING, "f:six,g")
    prop.setProperty(HTTP_SERVER_ERROR_STATUSES, "123-456,457,124-125,122")
    prop.setProperty(HTTP_CLIENT_ERROR_STATUSES, "111")
    prop.setProperty(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true")
    prop.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
    prop.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, "true")
    prop.setProperty(DB_CLIENT_HOST_SPLIT_BY_HOST, "true")
    prop.setProperty(SPLIT_BY_TAGS, "some.tag1,some.tag2,some.tag1")
    prop.setProperty(PARTIAL_FLUSH_MIN_SPANS, "15")
    prop.setProperty(TRACE_REPORT_HOSTNAME, "true")
    prop.setProperty(RUNTIME_CONTEXT_FIELD_INJECTION, "false")
    prop.setProperty(PROPAGATION_STYLE_EXTRACT, "Datadog, B3")
    prop.setProperty(PROPAGATION_STYLE_INJECT, "B3, Datadog")
    prop.setProperty(TRACE_PROPAGATION_EXTRACT_FIRST, "false")
    prop.setProperty(TRACE_PROPAGATION_BEHAVIOR_EXTRACT, "restart")
    prop.setProperty(JMX_FETCH_ENABLED, "false")
    prop.setProperty(JMX_FETCH_METRICS_CONFIGS, "/foo.yaml,/bar.yaml")
    prop.setProperty(JMX_FETCH_CHECK_PERIOD, "100")
    prop.setProperty(JMX_FETCH_REFRESH_BEANS_PERIOD, "200")
    prop.setProperty(JMX_FETCH_STATSD_HOST, "statsd host")
    prop.setProperty(JMX_FETCH_STATSD_PORT, "321")
    prop.setProperty(HEALTH_METRICS_ENABLED, "true")
    prop.setProperty(HEALTH_METRICS_STATSD_HOST, "metrics statsd host")
    prop.setProperty(HEALTH_METRICS_STATSD_PORT, "654")
    prop.setProperty(TRACE_SAMPLING_SERVICE_RULES, "a:1")
    prop.setProperty(TRACE_SAMPLING_OPERATION_RULES, "b:1")
    prop.setProperty(TRACE_SAMPLE_RATE, ".5")
    prop.setProperty(TRACE_RATE_LIMIT, "200")
    prop.setProperty(TRACE_LONG_RUNNING_ENABLED, "true")
    prop.setProperty(TRACE_LONG_RUNNING_FLUSH_INTERVAL, "250")

    prop.setProperty(TRACE_EXPERIMENTAL_FEATURES_ENABLED, "DD_TAGS, DD_TRACE_HTTP_CLIENT_TAG_QUERY_STRING")

    prop.setProperty(PROFILING_ENABLED, "true")
    prop.setProperty(PROFILING_URL, "new url")
    prop.setProperty(PROFILING_TAGS, "f:6,host:test-host")
    prop.setProperty(PROFILING_START_DELAY, "1111")
    prop.setProperty(PROFILING_START_FORCE_FIRST, "true")
    prop.setProperty(PROFILING_UPLOAD_PERIOD, "1112")
    prop.setProperty(PROFILING_TEMPLATE_OVERRIDE_FILE, "/path")
    prop.setProperty(PROFILING_UPLOAD_TIMEOUT, "1116")
    prop.setProperty(PROFILING_UPLOAD_COMPRESSION, "off")
    prop.setProperty(PROFILING_PROXY_HOST, "proxy-host")
    prop.setProperty(PROFILING_PROXY_PORT, "1118")
    prop.setProperty(PROFILING_PROXY_USERNAME, "proxy-username")
    prop.setProperty(PROFILING_PROXY_PASSWORD, "proxy-password")
    prop.setProperty(PROFILING_EXCEPTION_SAMPLE_LIMIT, "811")
    prop.setProperty(PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS, "1121")
    prop.setProperty(PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE, "1122")
    prop.setProperty(PROFILING_AGENTLESS, "true")

    prop.setProperty(REMOTE_CONFIGURATION_ENABLED, "true")
    prop.setProperty(REMOTE_CONFIG_URL, "remote config url")
    prop.setProperty(REMOTE_CONFIG_POLL_INTERVAL_SECONDS, "3")
    prop.setProperty(REMOTE_CONFIG_MAX_PAYLOAD_SIZE, "2")

    prop.setProperty(DYNAMIC_INSTRUMENTATION_ENABLED, "true")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_PROBE_FILE, "file location")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT, "10")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_UPLOAD_INTERVAL_SECONDS, "0.234")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE, "200")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_METRICS_ENABLED, "false")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED, "true")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_POLL_INTERVAL, "10")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL, "60")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE, "true")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_INSTRUMENT_THE_WORLD, "method")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_EXCLUDE_FILES, "exclude file")
    prop.setProperty(DYNAMIC_INSTRUMENTATION_SNAPSHOT_URL, "http://somehost:123/debugger/v1/input")
    prop.setProperty(EXCEPTION_REPLAY_ENABLED, "true")
    prop.setProperty(TRACE_X_DATADOG_TAGS_MAX_LENGTH, "128")
    prop.setProperty(JDK_SOCKET_ENABLED, "false")

    prop.setProperty(METRICS_OTEL_ENABLED, "True")
    prop.setProperty(METRICS_OTEL_INTERVAL, "11000")
    prop.setProperty(METRICS_OTEL_TIMEOUT, "9000")
    prop.setProperty(OTLP_METRICS_ENDPOINT, "http://localhost:4333/v1/metrics")
    prop.setProperty(OTLP_METRICS_HEADERS, "api-key=key,other-config-value=value")
    prop.setProperty(OTLP_METRICS_PROTOCOL, "http/protobuf")
    prop.setProperty(OTLP_METRICS_TIMEOUT, "5000")
    prop.setProperty(OTLP_METRICS_TEMPORALITY_PREFERENCE, "cumulative")

    when:
    Config config = Config.get(prop)

    then:
    config.configFileStatus == "no config file present"
    config.apiKey == "new api key" // we can still override via internal properties object
    config.site == "new site"
    config.serviceName == "something else"
    config.idGenerationStrategy.class.name.endsWith('$Sequential')
    config.traceEnabled == false
    config.writerType == "LoggingWriter"
    config.agentHost == "somehost"
    config.agentPort == 123
    config.agentUnixDomainSocket == "somepath"
    config.agentUrl == "http://somehost:123"
    config.prioritySamplingEnabled == false
    config.traceResolverEnabled == false
    config.serviceMapping == [a: "1:2"]
    config.mergedSpanTags == [b: "2", c: "3"]
    config.mergedJmxTags == [b: "2", d: "4", (RUNTIME_ID_TAG): config.getRuntimeId(), (SERVICE_TAG): config.serviceName]
    config.requestHeaderTags == [e: "five"]
    config.baggageMapping == [f: "six", g: "g"]
    config.httpServerErrorStatuses == toBitSet((122..457))
    config.httpClientErrorStatuses == toBitSet((111..111))
    config.httpClientSplitByDomain == true
    config.dbClientSplitByInstance == true
    config.dbClientSplitByInstanceTypeSuffix == true
    config.dbClientSplitByHost == true
    config.splitByTags == ["some.tag1", "some.tag2"].toSet()
    config.partialFlushMinSpans == 15
    config.reportHostName == true
    config.propagationStylesToExtract.toList() == [PropagationStyle.DATADOG, PropagationStyle.B3]
    config.propagationStylesToInject.toList() == [PropagationStyle.B3, PropagationStyle.DATADOG]
    config.tracePropagationStylesToExtract.toList() == [DATADOG, B3SINGLE, B3MULTI]
    config.tracePropagationStylesToInject.toList() == [B3SINGLE, B3MULTI, DATADOG]
    config.tracePropagationExtractFirst == false
    config.tracePropagationBehaviorExtract == TracePropagationBehaviorExtract.RESTART
    config.jmxFetchEnabled == false
    config.jmxFetchMetricsConfigs == ["/foo.yaml", "/bar.yaml"]
    config.jmxFetchCheckPeriod == 100
    config.jmxFetchRefreshBeansPeriod == 200
    config.jmxFetchStatsdHost == "statsd host"
    config.jmxFetchStatsdPort == 321

    config.healthMetricsEnabled == true
    config.healthMetricsStatsdHost == "metrics statsd host"
    config.healthMetricsStatsdPort == 654
    config.traceSamplingServiceRules == [a: "1"]
    config.traceSamplingOperationRules == [b: "1"]
    config.traceSampleRate == 0.5
    config.traceRateLimit == 200
    config.isLongRunningTraceEnabled()
    config.getLongRunningTraceFlushInterval() == 250

    config.experimentalFeaturesEnabled == ["DD_TAGS", "DD_TRACE_HTTP_CLIENT_TAG_QUERY_STRING"].toSet()

    config.profilingEnabled == true
    config.profilingUrl == "new url"
    config.mergedProfilingTags == [b: "2", f: "6", (HOST_TAG): "test-host", (RUNTIME_ID_TAG): config.getRuntimeId(), (RUNTIME_VERSION_TAG): config.getRuntimeVersion(), (SERVICE_TAG): config.serviceName, (LANGUAGE_TAG_KEY): LANGUAGE_TAG_VALUE]
    config.profilingStartDelay == 1111
    config.profilingStartForceFirst == true
    config.profilingUploadPeriod == 1112
    config.profilingUploadCompression == "off"
    config.profilingTemplateOverrideFile == "/path"
    config.profilingUploadTimeout == 1116
    config.profilingProxyHost == "proxy-host"
    config.profilingProxyPort == 1118
    config.profilingProxyUsername == "proxy-username"
    config.profilingProxyPassword == "proxy-password"
    config.profilingExceptionSampleLimit == 811
    config.profilingExceptionHistogramTopItems == 1121
    config.profilingExceptionHistogramMaxCollectionSize == 1122
    config.profilingAgentless == true

    config.remoteConfigEnabled == true
    config.finalRemoteConfigUrl == 'remote config url'
    config.remoteConfigPollIntervalSeconds == 3
    config.remoteConfigMaxPayloadSizeBytes == 2048

    config.dynamicInstrumentationEnabled == true
    config.getFinalDebuggerSnapshotUrl() == "http://somehost:123/debugger/v1/input"
    config.dynamicInstrumentationProbeFile == "file location"
    config.dynamicInstrumentationUploadTimeout == 10
    config.dynamicInstrumentationUploadFlushInterval == 234
    config.dynamicInstrumentationUploadBatchSize == 200
    config.dynamicInstrumentationMetricsEnabled == false
    config.dynamicInstrumentationClassFileDumpEnabled == true
    config.dynamicInstrumentationPollInterval == 10
    config.dynamicInstrumentationDiagnosticsInterval == 60
    config.dynamicInstrumentationVerifyByteCode == true
    config.dynamicInstrumentationInstrumentTheWorld == "method"
    config.dynamicInstrumentationExcludeFiles == "exclude file"
    config.debuggerExceptionEnabled == true
    config.xDatadogTagsMaxLength == 128
    config.jdkSocketEnabled == false

    config.metricsOtelEnabled
    config.metricsOtelInterval == 11000
    config.metricsOtelTimeout == 9000
    config.otlpMetricsEndpoint == "http://localhost:4333/v1/metrics"
    config.otlpMetricsHeaders["api-key"] == "key"
    config.otlpMetricsHeaders["other-config-value"] == "value"
    config.otlpMetricsProtocol == HTTP_PROTOBUF
    config.otlpMetricsTimeout == 5000
    config.otlpMetricsTemporalityPreference == CUMULATIVE

  }

  def "otel metrics: default values when configured incorrectly"() {
    setup:
    def prop = new Properties()

    prop.setProperty(METRICS_OTEL_ENABLED, "youhou")
    prop.setProperty(METRICS_OTEL_INTERVAL, "-1")
    prop.setProperty(METRICS_OTEL_TIMEOUT, "invalid")
    prop.setProperty(OTLP_METRICS_ENDPOINT, "invalid")
    prop.setProperty(OTLP_METRICS_HEADERS, "11")
    prop.setProperty(OTLP_METRICS_PROTOCOL, "invalid")
    prop.setProperty(OTLP_METRICS_TIMEOUT, "-34")
    prop.setProperty(OTLP_METRICS_TEMPORALITY_PREFERENCE, "invalid")

    when:
    Config config = Config.get(prop)

    then:
    !config.metricsOtelEnabled
    config.metricsOtelInterval == -1
    config.metricsOtelTimeout == 7500
    config.otlpMetricsEndpoint == "invalid"
    config.otlpMetricsHeaders == [:]
    config.otlpMetricsProtocol == HTTP_PROTOBUF
    config.otlpMetricsTimeout == -34
    config.otlpMetricsTemporalityPreference == DELTA
  }

  def "otel metrics: default values when not set"() {
    setup:
    def prop = new Properties()

    when:
    Config config = Config.get(prop)

    then:
    !config.metricsOtelEnabled
    config.metricsOtelInterval == 10000
    config.metricsOtelTimeout == 7500
    config.otlpMetricsEndpoint == "http://localhost:4318/v1/metrics"
    config.otlpMetricsHeaders == [:]
    config.otlpMetricsProtocol == HTTP_PROTOBUF
    config.otlpMetricsTimeout == 7500
    config.otlpMetricsTemporalityPreference == DELTA
  }


  def "otel metrics: check syntax for attributes and headers"() {
    setup:
    def prop = new Properties()
    prop.setProperty(OTLP_METRICS_HEADERS, "api,key=key")

    when:
    Config config = Config.get(prop)

    then:
    config.otlpMetricsHeaders.size() == 0
  }

  def "otel generic config via system properties - metrics enabled"() {
    setup:
    System.setProperty(PREFIX + METRICS_OTEL_ENABLED, "true")
    System.setProperty(OTEL_RESOURCE_ATTRIBUTES_PROP, "service.name=my=app,service.version=1.0.0,deployment.environment=production, message=blahblah")
    System.setProperty("otel.log.level", "warning")

    when:
    Config config = new Config()

    then:
    config.serviceName == "my=app"
    config.version == "1.0.0"
    config.env == "production"
    config.tags.size() == 3
    config.tags["message"] == "blahblah"
    config.tags["env"] == "production"
    config.tags["version"] == "1.0.0"
    config.logLevel == "warning"
  }

  def "otel generic config via system properties - trace enabled"() {
    setup:
    System.setProperty(PREFIX + TRACE_OTEL_ENABLED, "true")
    System.setProperty(OTEL_RESOURCE_ATTRIBUTES_PROP, "service.name=my=app,service.version=1.0.0,deployment.environment=production, message=blahblah")
    System.setProperty("otel.log.level", "warning")

    when:
    Config config = new Config()

    then:
    config.serviceName == "my=app"
    config.version == "1.0.0"
    config.env == "production"
    config.tags.size() == 3
    config.tags["message"] == "blahblah"
    config.tags["env"] == "production"
    config.tags["version"] == "1.0.0"
    config.logLevel == "warning"
  }


  def "otel generic config via env var - metrics enabled"() {
    setup:
    environmentVariables.set(DD_METRICS_OTEL_ENABLED_ENV, "true")
    environmentVariables.set(OTEL_RESOURCE_ATTRIBUTES_ENV, "service.name=my=app,service.version=1.0.0,deployment.environment=production, message=blahblah")
    environmentVariables.set("OTEL_LOG_LEVEL", "error")
    when:
    Config config = new Config()

    then:
    config.serviceName == "my=app"
    config.version == "1.0.0"
    config.env == "production"
    config.tags.size() == 3
    config.tags["message"] == "blahblah"
    config.tags["env"] == "production"
    config.tags["version"] == "1.0.0"
    config.logLevel == "error"
  }

  def "otel generic config via env var - traces enabled"() {
    setup:
    environmentVariables.set("DD_TRACE_OTEL_ENABLED", "true")
    environmentVariables.set(OTEL_RESOURCE_ATTRIBUTES_ENV, "service.name=my=app,service.version=1.0.0,deployment.environment=production, message=blahblah")
    environmentVariables.set("OTEL_LOG_LEVEL", "error")
    when:
    Config config = new Config()

    then:
    config.serviceName == "my=app"
    config.version == "1.0.0"
    config.env == "production"
    config.tags.size() == 3
    config.tags["message"] == "blahblah"
    config.tags["env"] == "production"
    config.tags["version"] == "1.0.0"
    config.logLevel == "error"
  }

  def "otel metrics: fallback keys"() {
    setup:
    System.setProperty(DD_METRICS_OTEL_ENABLED_PROP, "true")
    System.setProperty(OTEL_EXPORTER_OTLP_PROTOCOL_PROP, "http/json")
    System.setProperty(OTEL_EXPORTER_OTLP_ENDPOINT_PROP,"http://localhost:4319")
    System.setProperty(OTEL_EXPORTER_OTLP_HEADERS_PROP,"api-key=key,other-config-value=value")
    System.setProperty(OTEL_EXPORTER_OTLP_TIMEOUT_PROP,"1000")

    when:
    Config config = new Config()

    then:
    config.otlpMetricsProtocol == HTTP_JSON
    config.otlpMetricsEndpoint == "http://localhost:4319/v1/metrics"
    config.otlpMetricsHeaders.size() == 2
    config.otlpMetricsHeaders["api-key"] == "key"
    config.otlpMetricsHeaders["other-config-value"] == "value"
    config.otlpMetricsTimeout == 1000
  }

  def "otel metrics: fallback key endpoint"() {
    setup:
    System.setProperty(DD_METRICS_OTEL_ENABLED_PROP, "true")
    System.setProperty(OTEL_EXPORTER_OTLP_PROTOCOL_PROP, "http/json")
    System.setProperty(PREFIX + TRACE_AGENT_URL,"http://192.168.0.3:8126")

    when:
    Config config = new Config()

    then:
    config.agentHost == "192.168.0.3"
    config.otlpMetricsProtocol == HTTP_JSON
    config.otlpMetricsEndpoint == "http://192.168.0.3:4318/v1/metrics"
  }

  def "otel metrics: fallback key endpoint 2"() {
    setup:
    System.setProperty(DD_METRICS_OTEL_ENABLED_PROP, "true")
    System.setProperty(PREFIX + TRACE_AGENT_URL,"'/tmp/ddagent/trace.sock'")

    when:
    Config config = new Config()

    then:
    config.agentHost == "localhost"
    config.otlpMetricsProtocol == HTTP_PROTOBUF
    config.otlpMetricsEndpoint == "http://localhost:4318/v1/metrics"
  }


  def "specify overrides via system properties"() {
    setup:
    System.setProperty(PREFIX + API_KEY, "new api key")
    System.setProperty(PREFIX + SITE, "new site")
    System.setProperty(PREFIX + SERVICE_NAME, "something else")
    System.setProperty(PREFIX + TRACE_ENABLED, "false")
    System.setProperty(PREFIX + WRITER_TYPE, "LoggingWriter")
    System.setProperty(PREFIX + PRIORITIZATION_TYPE, "EnsureTrace")
    System.setProperty(PREFIX + AGENT_HOST, "somehost")
    System.setProperty(PREFIX + TRACE_AGENT_PORT, "123")
    System.setProperty(PREFIX + AGENT_UNIX_DOMAIN_SOCKET, "somepath")
    System.setProperty(PREFIX + AGENT_PORT_LEGACY, "456")
    System.setProperty(PREFIX + PRIORITY_SAMPLING, "false")
    System.setProperty(PREFIX + TRACE_RESOLVER_ENABLED, "false")
    System.setProperty(PREFIX + SERVICE_MAPPING, "a:1")
    System.setProperty(PREFIX + GLOBAL_TAGS, "b:2")
    System.setProperty(PREFIX + SPAN_TAGS, "c:3")
    System.setProperty(PREFIX + JMX_TAGS, "d:4")
    System.setProperty(PREFIX + HEADER_TAGS, "e:five")
    System.setProperty(PREFIX + BAGGAGE_MAPPING, "f:six,g")
    System.setProperty(PREFIX + HTTP_SERVER_ERROR_STATUSES, "123-456,457,124-125,122")
    System.setProperty(PREFIX + HTTP_CLIENT_ERROR_STATUSES, "111")
    System.setProperty(PREFIX + HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true")
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, "true")
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_HOST, "true")
    System.setProperty(PREFIX + SPLIT_BY_TAGS, "some.tag3, some.tag2, some.tag1")
    System.setProperty(PREFIX + PARTIAL_FLUSH_MIN_SPANS, "25")
    System.setProperty(PREFIX + TRACE_REPORT_HOSTNAME, "true")
    System.setProperty(PREFIX + RUNTIME_CONTEXT_FIELD_INJECTION, "false")
    System.setProperty(PREFIX + PROPAGATION_STYLE_EXTRACT, "Datadog, B3")
    System.setProperty(PREFIX + PROPAGATION_STYLE_INJECT, "B3, Datadog")
    System.setProperty(PREFIX + TRACE_PROPAGATION_EXTRACT_FIRST, "false")
    System.setProperty(PREFIX + TRACE_PROPAGATION_BEHAVIOR_EXTRACT, "restart")
    System.setProperty(PREFIX + JMX_FETCH_ENABLED, "false")
    System.setProperty(PREFIX + JMX_FETCH_METRICS_CONFIGS, "/foo.yaml,/bar.yaml")
    System.setProperty(PREFIX + JMX_FETCH_CHECK_PERIOD, "100")
    System.setProperty(PREFIX + JMX_FETCH_REFRESH_BEANS_PERIOD, "200")
    System.setProperty(PREFIX + JMX_FETCH_STATSD_HOST, "statsd host")
    System.setProperty(PREFIX + JMX_FETCH_STATSD_PORT, "321")
    System.setProperty(PREFIX + HEALTH_METRICS_ENABLED, "true")
    System.setProperty(PREFIX + HEALTH_METRICS_STATSD_HOST, "metrics statsd host")
    System.setProperty(PREFIX + HEALTH_METRICS_STATSD_PORT, "654")
    System.setProperty(PREFIX + TRACE_SAMPLING_SERVICE_RULES, "a:1")
    System.setProperty(PREFIX + TRACE_SAMPLING_OPERATION_RULES, "b:1")
    System.setProperty(PREFIX + TRACE_SAMPLE_RATE, ".5")
    System.setProperty(PREFIX + TRACE_RATE_LIMIT, "200")
    System.setProperty(PREFIX + TRACE_LONG_RUNNING_ENABLED, "true")
    System.setProperty(PREFIX + TRACE_LONG_RUNNING_FLUSH_INTERVAL, "333")

    System.setProperty(PREFIX + TRACE_EXPERIMENTAL_FEATURES_ENABLED, "DD_TAGS, DD_TRACE_HTTP_CLIENT_TAG_QUERY_STRING")

    System.setProperty(PREFIX + PROFILING_ENABLED, "true")
    System.setProperty(PREFIX + PROFILING_URL, "new url")
    System.setProperty(PREFIX + PROFILING_TAGS, "f:6,host:test-host")
    System.setProperty(PREFIX + PROFILING_START_DELAY, "1111")
    System.setProperty(PREFIX + PROFILING_START_FORCE_FIRST, "true")
    System.setProperty(PREFIX + PROFILING_UPLOAD_PERIOD, "1112")
    System.setProperty(PREFIX + PROFILING_TEMPLATE_OVERRIDE_FILE, "/path")
    System.setProperty(PREFIX + PROFILING_UPLOAD_TIMEOUT, "1116")
    System.setProperty(PREFIX + PROFILING_UPLOAD_COMPRESSION, "off")
    System.setProperty(PREFIX + PROFILING_PROXY_HOST, "proxy-host")
    System.setProperty(PREFIX + PROFILING_PROXY_PORT, "1118")
    System.setProperty(PREFIX + PROFILING_PROXY_USERNAME, "proxy-username")
    System.setProperty(PREFIX + PROFILING_PROXY_PASSWORD, "proxy-password")
    System.setProperty(PREFIX + PROFILING_EXCEPTION_SAMPLE_LIMIT, "811")
    System.setProperty(PREFIX + PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS, "1121")
    System.setProperty(PREFIX + PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE, "1122")
    System.setProperty(PREFIX + PROFILING_AGENTLESS, "true")

    System.setProperty(PREFIX + REMOTE_CONFIGURATION_ENABLED, "true")
    System.setProperty(PREFIX + REMOTE_CONFIG_URL, "remote config url")
    System.setProperty(PREFIX + REMOTE_CONFIG_POLL_INTERVAL_SECONDS, "3")
    System.setProperty(PREFIX + REMOTE_CONFIG_MAX_PAYLOAD_SIZE, "2")

    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_ENABLED, "true")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_SNAPSHOT_URL, "snapshot url")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_PROBE_FILE, "file location")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT, "10")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL, "1000")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE, "200")
    System.setProperty(PREFIX + REMOTE_CONFIG_MAX_PAYLOAD_SIZE, "2")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_METRICS_ENABLED, "false")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED, "true")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_POLL_INTERVAL, "10")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL, "60")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE, "true")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_INSTRUMENT_THE_WORLD, "method")
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_EXCLUDE_FILES, "exclude file")
    System.setProperty(PREFIX + TRACE_X_DATADOG_TAGS_MAX_LENGTH, "128")

    System.setProperty(PREFIX + METRICS_OTEL_ENABLED, "True")
    System.setProperty(OTEL_METRIC_EXPORT_INTERVAL_PROP, "11000")
    System.setProperty(OTEL_METRIC_EXPORT_TIMEOUT_PROP, "9000")
    System.setProperty(OTEL_EXPORTER_OTLP_METRICS_ENDPOINT_PROP, "http://localhost:4333/v1/metrics")
    System.setProperty(OTEL_EXPORTER_OTLP_METRICS_HEADERS_PROP, "api-key=key,other-config-value=value")
    System.setProperty(OTEL_EXPORTER_OTLP_METRICS_PROTOCOL_PROP, "http/protobuf")
    System.setProperty(OTEL_EXPORTER_OTLP_TIMEOUT_PROP, "5000")
    System.setProperty(OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE_PROP, "cumulative")
    System.setProperty(OTEL_RESOURCE_ATTRIBUTES_PROP, "service.name=my=app,service.version=1.0.0,deployment.environment=production")

    when:
    Config config = new Config()

    then:
    config.apiKey == null // system properties cannot be used to provide a key
    config.site == "new site"
    config.serviceName == "something else"
    config.traceEnabled == false
    config.writerType == "LoggingWriter"
    config.agentHost == "somehost"
    config.agentPort == 123
    config.agentUnixDomainSocket == "somepath"
    config.agentUrl == "http://somehost:123"
    config.prioritySamplingEnabled == false
    config.traceResolverEnabled == false
    config.serviceMapping == [a: "1"]
    config.mergedSpanTags == [b: "2", c: "3", env: "production", version:"1.0.0"]
    config.mergedJmxTags == [b: "2", d: "4", (RUNTIME_ID_TAG): config.getRuntimeId(), (SERVICE_TAG): config.serviceName, env: "production", version:"1.0.0"]
    config.requestHeaderTags == [e: "five"]
    config.baggageMapping == [f: "six", g: "g"]
    config.httpServerErrorStatuses == toBitSet((122..457))
    config.httpClientErrorStatuses == toBitSet((111..111))
    config.httpClientSplitByDomain == true
    config.dbClientSplitByInstance == true
    config.dbClientSplitByInstanceTypeSuffix == true
    config.dbClientSplitByHost == true
    config.splitByTags == ["some.tag3", "some.tag2", "some.tag1"].toSet()
    config.partialFlushMinSpans == 25
    config.reportHostName == true
    config.propagationStylesToExtract.toList() == [PropagationStyle.DATADOG, PropagationStyle.B3]
    config.propagationStylesToInject.toList() == [PropagationStyle.B3, PropagationStyle.DATADOG]
    config.tracePropagationStylesToExtract.toList() == [DATADOG, B3SINGLE, B3MULTI]
    config.tracePropagationStylesToInject.toList() == [B3SINGLE, B3MULTI, DATADOG]
    config.tracePropagationExtractFirst == false
    config.tracePropagationBehaviorExtract == TracePropagationBehaviorExtract.RESTART
    config.jmxFetchEnabled == false
    config.jmxFetchMetricsConfigs == ["/foo.yaml", "/bar.yaml"]
    config.jmxFetchCheckPeriod == 100
    config.jmxFetchRefreshBeansPeriod == 200
    config.jmxFetchStatsdHost == "statsd host"
    config.jmxFetchStatsdPort == 321

    config.healthMetricsEnabled == true
    config.healthMetricsStatsdHost == "metrics statsd host"
    config.healthMetricsStatsdPort == 654
    config.traceSamplingServiceRules == [a: "1"]
    config.traceSamplingOperationRules == [b: "1"]
    config.traceSampleRate == 0.5
    config.traceRateLimit == 200
    config.isLongRunningTraceEnabled()
    config.getLongRunningTraceFlushInterval() == 333
    config.traceRateLimit == 200

    config.experimentalFeaturesEnabled == ["DD_TAGS", "DD_TRACE_HTTP_CLIENT_TAG_QUERY_STRING"].toSet()

    config.profilingEnabled == true
    config.profilingUrl == "new url"
    config.mergedProfilingTags == [b: "2", f: "6", (HOST_TAG): "test-host", (RUNTIME_ID_TAG): config.getRuntimeId(), (RUNTIME_VERSION_TAG): config.getRuntimeVersion(), (SERVICE_TAG): config.serviceName, (LANGUAGE_TAG_KEY): LANGUAGE_TAG_VALUE, env: "production", version:"1.0.0"]
    config.profilingStartDelay == 1111
    config.profilingStartForceFirst == true
    config.profilingUploadPeriod == 1112
    config.profilingTemplateOverrideFile == "/path"
    config.profilingUploadTimeout == 1116
    config.profilingUploadCompression == "off"
    config.profilingProxyHost == "proxy-host"
    config.profilingProxyPort == 1118
    config.profilingProxyUsername == "proxy-username"
    config.profilingProxyPassword == "proxy-password"
    config.profilingExceptionSampleLimit == 811
    config.profilingExceptionHistogramTopItems == 1121
    config.profilingExceptionHistogramMaxCollectionSize == 1122
    config.profilingAgentless == true

    config.remoteConfigEnabled == true
    config.finalRemoteConfigUrl == 'remote config url'
    config.remoteConfigPollIntervalSeconds == 3
    config.remoteConfigMaxPayloadSizeBytes == 2 * 1024

    config.dynamicInstrumentationEnabled == true
    config.dynamicInstrumentationProbeFile == "file location"
    config.dynamicInstrumentationUploadTimeout == 10
    config.dynamicInstrumentationUploadFlushInterval == 1000
    config.dynamicInstrumentationUploadBatchSize == 200
    config.dynamicInstrumentationMetricsEnabled == false
    config.dynamicInstrumentationClassFileDumpEnabled == true
    config.dynamicInstrumentationPollInterval == 10
    config.dynamicInstrumentationDiagnosticsInterval == 60
    config.dynamicInstrumentationVerifyByteCode == true
    config.dynamicInstrumentationInstrumentTheWorld == "method"
    config.dynamicInstrumentationExcludeFiles == "exclude file"

    config.xDatadogTagsMaxLength == 128

    config.metricsOtelEnabled
    config.version == "1.0.0"
    config.env ==  "production"
    config.metricsOtelInterval == 11000
    config.metricsOtelTimeout == 9000
    config.otlpMetricsEndpoint == "http://localhost:4333/v1/metrics"
    config.otlpMetricsHeaders["api-key"] == "key"
    config.otlpMetricsHeaders["other-config-value"] == "value"
    config.otlpMetricsProtocol == HTTP_PROTOBUF
    config.otlpMetricsTimeout == 5000
    config.otlpMetricsTemporalityPreference == CUMULATIVE
  }

  def "specify overrides via env vars"() {
    setup:
    environmentVariables.set(DD_API_KEY_ENV, "test-api-key")
    environmentVariables.set(DD_SERVICE_NAME_ENV, "still something else")
    environmentVariables.set(DD_TRACE_ENABLED_ENV, "false")
    environmentVariables.set(DD_WRITER_TYPE_ENV, "LoggingWriter")
    environmentVariables.set(DD_PRIORITIZATION_TYPE_ENV, "EnsureTrace")
    environmentVariables.set(DD_PROPAGATION_STYLE_EXTRACT, "B3 Datadog")
    environmentVariables.set(DD_PROPAGATION_STYLE_INJECT, "Datadog B3")
    environmentVariables.set(DD_TRACE_PROPAGATION_EXTRACT_FIRST, "false")
    environmentVariables.set(DD_JMXFETCH_METRICS_CONFIGS_ENV, "some/file")
    environmentVariables.set(DD_TRACE_REPORT_HOSTNAME, "true")
    environmentVariables.set(DD_TRACE_X_DATADOG_TAGS_MAX_LENGTH, "42")
    environmentVariables.set(DD_TRACE_LONG_RUNNING_ENABLED, "true")
    environmentVariables.set(DD_TRACE_LONG_RUNNING_FLUSH_INTERVAL, "81")
    environmentVariables.set(DD_TRACE_HEADER_TAGS, "*")
    environmentVariables.set(DD_METRICS_OTEL_ENABLED_ENV, "True")
    environmentVariables.set(OTEL_RESOURCE_ATTRIBUTES_ENV, "service.name=my=app,service.version=1.0.0,deployment.environment=production")
    environmentVariables.set(OTEL_METRIC_EXPORT_INTERVAL_ENV, "11000")
    environmentVariables.set(OTEL_METRIC_EXPORT_TIMEOUT_ENV, "9000")
    environmentVariables.set(OTEL_EXPORTER_OTLP_METRICS_ENDPOINT_ENV, "http://localhost:4333/v1/metrics")
    environmentVariables.set(OTEL_EXPORTER_OTLP_METRICS_HEADERS_ENV, "api-key=key,other-config-value=value")
    environmentVariables.set(OTEL_EXPORTER_OTLP_METRICS_PROTOCOL_ENV, "http/protobuf")
    environmentVariables.set(OTEL_EXPORTER_OTLP_METRICS_TIMEOUT_ENV, "5000")
    environmentVariables.set(OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE_ENV, "cumulative")

    when:
    def config = new Config()

    then:
    config.apiKey == "test-api-key"
    config.serviceName == "still something else"
    config.traceEnabled == false
    config.writerType == "LoggingWriter"
    config.propagationStylesToExtract.toList() == [PropagationStyle.B3, PropagationStyle.DATADOG]
    config.propagationStylesToInject.toList() == [PropagationStyle.DATADOG, PropagationStyle.B3]
    config.tracePropagationStylesToExtract.toList() == [B3SINGLE, B3MULTI, DATADOG]
    config.tracePropagationStylesToInject.toList() == [DATADOG, B3SINGLE, B3MULTI]
    config.tracePropagationExtractFirst == false
    config.jmxFetchMetricsConfigs == ["some/file"]
    config.reportHostName == true
    config.xDatadogTagsMaxLength == 42
    config.isLongRunningTraceEnabled()
    config.getLongRunningTraceFlushInterval() == 81
    config.requestHeaderTags == ["*":"http.request.headers."]
    config.responseHeaderTags == ["*":"http.response.headers."]
    config.metricsOtelEnabled
    config.metricsOtelInterval == 11000
    config.metricsOtelTimeout == 9000
    config.otlpMetricsEndpoint == "http://localhost:4333/v1/metrics"
    config.otlpMetricsHeaders["api-key"] == "key"
    config.otlpMetricsHeaders["other-config-value"] == "value"
    config.otlpMetricsProtocol == HTTP_PROTOBUF
    config.otlpMetricsTimeout == 5000
    config.otlpMetricsTemporalityPreference == CUMULATIVE
  }

  def "sys props override env vars"() {
    setup:
    environmentVariables.set(DD_SERVICE_NAME_ENV, "still something else")
    environmentVariables.set(DD_WRITER_TYPE_ENV, "LoggingWriter")
    environmentVariables.set(DD_PRIORITIZATION_TYPE_ENV, "EnsureTrace")
    environmentVariables.set(DD_TRACE_AGENT_PORT_ENV, "777")
    environmentVariables.set(DD_TRACE_LONG_RUNNING_ENABLED, "false")

    System.setProperty(PREFIX + SERVICE_NAME, "what we actually want")
    System.setProperty(PREFIX + WRITER_TYPE, "DDAgentWriter")
    System.setProperty(PREFIX + PRIORITIZATION_TYPE, "FastLane")
    System.setProperty(PREFIX + AGENT_HOST, "somewhere")
    System.setProperty(PREFIX + TRACE_AGENT_PORT, "123")
    System.setProperty(PREFIX + TRACE_LONG_RUNNING_ENABLED, "true")

    when:
    def config = new Config()

    then:
    config.serviceName == "what we actually want"
    config.writerType == "DDAgentWriter"
    config.agentHost == "somewhere"
    config.agentPort == 123
    config.agentUrl == "http://somewhere:123"
    config.longRunningTraceEnabled
    config.longRunningTraceFlushInterval == 120
  }

  def "default when configured incorrectly"() {
    setup:
    System.setProperty(PREFIX + SERVICE_NAME, " ")
    System.setProperty(PREFIX + TRACE_ENABLED, " ")
    System.setProperty(PREFIX + WRITER_TYPE, " ")
    System.setProperty(PREFIX + PRIORITIZATION_TYPE, " ")
    System.setProperty(PREFIX + AGENT_HOST, " ")
    System.setProperty(PREFIX + TRACE_AGENT_PORT, " ")
    System.setProperty(PREFIX + AGENT_PORT_LEGACY, "invalid")
    System.setProperty(PREFIX + PRIORITY_SAMPLING, "3")
    System.setProperty(PREFIX + TRACE_RESOLVER_ENABLED, " ")
    System.setProperty(PREFIX + SERVICE_MAPPING, " ")
    System.setProperty(PREFIX + HEADER_TAGS, "1")
    System.setProperty(PREFIX + BAGGAGE_MAPPING, "1")
    System.setProperty(PREFIX + SPAN_TAGS, "invalid")
    System.setProperty(PREFIX + HTTP_SERVER_ERROR_STATUSES, "1111")
    System.setProperty(PREFIX + HTTP_CLIENT_ERROR_STATUSES, "1:1")
    System.setProperty(PREFIX + HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "invalid")
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "invalid")
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_HOST, "invalid")
    System.setProperty(PREFIX + PROPAGATION_STYLE_EXTRACT, "some garbage")
    System.setProperty(PREFIX + PROPAGATION_STYLE_INJECT, " ")
    System.setProperty(PREFIX + TRACE_LONG_RUNNING_ENABLED, "invalid")
    System.setProperty(PREFIX + TRACE_LONG_RUNNING_FLUSH_INTERVAL, "invalid")
    System.setProperty(PREFIX + TRACE_EXPERIMENTAL_FEATURES_ENABLED, " ")

    when:
    def config = new Config()

    then:
    config.serviceName == " "
    config.traceEnabled == true
    config.writerType == " "
    config.agentHost == " "
    config.agentPort == 8126
    config.agentUrl == "http:// :8126"
    config.prioritySamplingEnabled == false
    config.traceResolverEnabled == true
    config.serviceMapping == [:]
    config.mergedSpanTags == [:]
    config.requestHeaderTags == [:]
    config.baggageMapping == [:]
    config.httpServerErrorStatuses == toBitSet((500..599))
    config.httpClientErrorStatuses == toBitSet((400..499))
    config.httpClientSplitByDomain == false
    config.dbClientSplitByInstance == false
    config.dbClientSplitByInstanceTypeSuffix == false
    config.dbClientSplitByHost == false
    config.splitByTags == [].toSet()
    config.propagationStylesToExtract.toList() == [PropagationStyle.DATADOG]
    config.propagationStylesToInject.toList() == [PropagationStyle.DATADOG]
    config.tracePropagationStylesToExtract.toList() == [DATADOG, TRACECONTEXT, BAGGAGE]
    config.tracePropagationStylesToInject.toList() == [DATADOG, TRACECONTEXT, BAGGAGE]
    config.longRunningTraceEnabled == false
    config.experimentalFeaturesEnabled == [].toSet()
  }

  def "sys props and env vars overrides for trace_agent_port and agent_port_legacy as expected"() {
    setup:
    if (overridePortEnvVar) {
      environmentVariables.set(DD_TRACE_AGENT_PORT_ENV, "777")
    }
    if (overrideLegacyPortEnvVar) {
      environmentVariables.set(DD_AGENT_PORT_LEGACY_ENV, "888")
    }

    if (overridePort) {
      System.setProperty(PREFIX + TRACE_AGENT_PORT, "123")
    }
    if (overrideLegacyPort) {
      System.setProperty(PREFIX + AGENT_PORT_LEGACY, "456")
    }

    when:
    def config = new Config()

    then:
    config.agentPort == expectedPort

    where:
    // spotless:off
    overridePort | overrideLegacyPort | overridePortEnvVar | overrideLegacyPortEnvVar | expectedPort
    true         | true               | false              | false                    | 123
    true         | false              | false              | false                    | 123
    false        | true               | false              | false                    | 456
    false        | false              | false              | false                    | 8126
    true         | true               | true               | false                    | 123
    true         | false              | true               | false                    | 123
    false        | true               | true               | false                    | 456 // legacy port gets picked up instead.
    false        | false              | true               | false                    | 777 // env var gets picked up instead.
    true         | true               | false              | true                     | 123
    true         | false              | false              | true                     | 123
    false        | true               | false              | true                     | 456
    false        | false              | false              | true                     | 888 // legacy env var gets picked up instead.
    true         | true               | true               | true                     | 123
    true         | false              | true               | true                     | 123
    false        | true               | true               | true                     | 456 // legacy port gets picked up instead.
    false        | false              | true               | true                     | 777 // env var gets picked up instead.
    // spotless:on
  }

  // FIXME: this seems to be a repeated test
  def "sys props override properties"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(SERVICE_NAME, "something else")
    properties.setProperty(TRACE_ENABLED, "false")
    properties.setProperty(WRITER_TYPE, "LoggingWriter")
    properties.setProperty(PRIORITIZATION_TYPE, "EnsureTrace")
    properties.setProperty(AGENT_HOST, "somehost")
    properties.setProperty(TRACE_AGENT_PORT, "123")
    properties.setProperty(AGENT_UNIX_DOMAIN_SOCKET, "somepath")
    properties.setProperty(PRIORITY_SAMPLING, "false")
    properties.setProperty(TRACE_RESOLVER_ENABLED, "false")
    properties.setProperty(SERVICE_MAPPING, "a:1")
    properties.setProperty(GLOBAL_TAGS, "b:2")
    properties.setProperty(SPAN_TAGS, "c:3")
    properties.setProperty(JMX_TAGS, "d:4")
    properties.setProperty(HEADER_TAGS, "e:five")
    properties.setProperty(BAGGAGE_MAPPING, "f:six,g")
    properties.setProperty(HTTP_SERVER_ERROR_STATUSES, "123-456,457,124-125,122")
    properties.setProperty(HTTP_CLIENT_ERROR_STATUSES, "111")
    properties.setProperty(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true")
    properties.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
    properties.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, "true")
    properties.setProperty(DB_CLIENT_HOST_SPLIT_BY_HOST, "true")
    properties.setProperty(PARTIAL_FLUSH_MIN_SPANS, "15")
    properties.setProperty(PROPAGATION_STYLE_EXTRACT, "B3 Datadog")
    properties.setProperty(PROPAGATION_STYLE_INJECT, "Datadog B3")
    properties.setProperty(JMX_FETCH_METRICS_CONFIGS, "/foo.yaml,/bar.yaml")
    properties.setProperty(JMX_FETCH_CHECK_PERIOD, "100")
    properties.setProperty(JMX_FETCH_REFRESH_BEANS_PERIOD, "200")
    properties.setProperty(JMX_FETCH_STATSD_HOST, "statsd host")
    properties.setProperty(JMX_FETCH_STATSD_PORT, "321")

    when:
    def config = Config.get(properties)

    then:
    config.serviceName == "something else"
    config.traceEnabled == false
    config.writerType == "LoggingWriter"
    config.agentHost == "somehost"
    config.agentPort == 123
    config.agentUnixDomainSocket == "somepath"
    config.agentUrl == "http://somehost:123"
    config.prioritySamplingEnabled == false
    config.traceResolverEnabled == false
    config.serviceMapping == [a: "1"]
    config.mergedSpanTags == [b: "2", c: "3"]
    config.mergedJmxTags == [b: "2", d: "4", (RUNTIME_ID_TAG): config.getRuntimeId(), (SERVICE_TAG): config.serviceName]
    config.requestHeaderTags == [e: "five"]
    config.baggageMapping == [f: "six",g: "g"]
    config.httpServerErrorStatuses == toBitSet((122..457))
    config.httpClientErrorStatuses == toBitSet((111..111))
    config.httpClientSplitByDomain == true
    config.dbClientSplitByInstance == true
    config.dbClientSplitByInstanceTypeSuffix == true
    config.dbClientSplitByHost == true
    config.splitByTags == [].toSet()
    config.partialFlushMinSpans == 15
    config.propagationStylesToExtract.toList() == [PropagationStyle.B3, PropagationStyle.DATADOG]
    config.propagationStylesToInject.toList() == [PropagationStyle.DATADOG, PropagationStyle.B3]
    config.tracePropagationStylesToExtract.toList() == [B3SINGLE, B3MULTI, DATADOG]
    config.tracePropagationStylesToInject.toList() == [DATADOG, B3SINGLE, B3MULTI]
    config.jmxFetchMetricsConfigs == ["/foo.yaml", "/bar.yaml"]
    config.jmxFetchCheckPeriod == 100
    config.jmxFetchRefreshBeansPeriod == 200
    config.jmxFetchStatsdHost == "statsd host"
    config.jmxFetchStatsdPort == 321
  }

  def "override null properties"() {
    when:
    def config = Config.get(null)

    then:
    config.serviceName == "unnamed-java-app"
    config.writerType == "DDAgentWriter"
  }

  def "override empty properties"() {
    setup:
    Properties properties = new Properties()

    when:
    def config = Config.get(properties)

    then:
    config.serviceName == "unnamed-java-app"
    config.writerType == "DDAgentWriter"
  }

  def "override non empty properties"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty("foo", "bar")

    when:
    def config = Config.get(properties)

    then:
    config.serviceName == "unnamed-java-app"
    config.writerType == "DDAgentWriter"
  }

  def "captured env props override default props"() {
    setup:
    def capturedEnv = [(SERVICE_NAME): "test service name"]
    FixedCapturedEnvironment.useFixedEnv(capturedEnv)

    when:
    def config = new Config()

    then:
    config.serviceName == "test service name"
  }

  def "specify props override captured env props"() {
    setup:
    def prop = new Properties()
    prop.setProperty(SERVICE_NAME, "what actually wants")

    def capturedEnv = [(SERVICE_NAME): "something else"]
    FixedCapturedEnvironment.useFixedEnv(capturedEnv)

    when:
    def config = Config.get(prop)

    then:
    config.serviceName == "what actually wants"
  }

  def "sys props override captured env props"() {
    setup:
    System.setProperty(PREFIX + SERVICE_NAME, "what actually wants")

    def capturedEnv = [(SERVICE_NAME): "something else"]
    FixedCapturedEnvironment.useFixedEnv(capturedEnv)

    when:
    def config = new Config()

    then:
    config.serviceName == "what actually wants"
  }

  def "env vars override captured env props"() {
    setup:
    environmentVariables.set(DD_SERVICE_NAME_ENV, "what actually wants")

    def capturedEnv = [(SERVICE_NAME): "something else"]
    FixedCapturedEnvironment.useFixedEnv(capturedEnv)

    when:
    def config = new Config()

    then:
    config.serviceName == "what actually wants"
  }

  def "verify mapping configs on tracer for #mapString"() {
    setup:
    System.setProperty(PREFIX + HEADER_TAGS + ".legacy.parsing.enabled", "true")
    System.setProperty(PREFIX + SERVICE_MAPPING, mapString)
    System.setProperty(PREFIX + SPAN_TAGS, mapString)
    System.setProperty(PREFIX + HEADER_TAGS, mapString)
    System.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rqh1")
    System.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsh1")
    def props = new Properties()
    props.setProperty(HEADER_TAGS + ".legacy.parsing.enabled", "true")
    props.setProperty(SERVICE_MAPPING, mapString)
    props.setProperty(SPAN_TAGS, mapString)
    props.setProperty(HEADER_TAGS, mapString)
    props.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rqh1")
    props.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsh1")

    when:
    def config = new Config()
    def propConfig = Config.get(props)

    then:
    config.serviceMapping == map
    config.spanTags == map
    config.requestHeaderTags == map
    config.responseHeaderTags == [:]
    propConfig.serviceMapping == map
    propConfig.spanTags == map
    propConfig.requestHeaderTags == map
    propConfig.responseHeaderTags == [:]

    where:
    // spotless:off
    mapString                                                     | map
    "a:1, a:2, a:3"                                               | [a: "3"]
    "a:b,c:d,e:"                                                  | [a: "b", c: "d"]
    // space separated
    "a:1  a:2  a:3"                                               | [a: "3"]
    "a:b c:d e:"                                                  | [a: "b", c: "d"]
    // More different string variants:
    "a:"                                                          | [:]
    "a:a;"                                                        | [a: "a;"]
    "a:1, a:2, a:3"                                               | [a: "3"]
    "a:1  a:2  a:3"                                               | [a: "3"]
    "a:b,c:d,e:"                                                  | [a: "b", c: "d"]
    "a:b c:d e:"                                                  | [a: "b", c: "d"]
    "key 1!:va|ue_1,"                                             | ["key 1!": "va|ue_1"]
    "key 1!:va|ue_1 "                                             | ["key 1!": "va|ue_1"]
    " key1 :value1 ,\t key2:  value2"                             | [key1: "value1", key2: "value2"]
    "a:b,c,d"                                                     | [:]
    "a:b,c,d,k:v"                                                 | [:]
    "key1 :value1  \t key2:  value2"                              | [key1: "value1", key2: "value2"]
    "dyno:web.1 dynotype:web buildpackversion:dev appname:******" | ["dyno": "web.1", "dynotype": "web", "buildpackversion": "dev", "appname": "******"]
    "is:val:id"                                                   | [is: "val:id"]
    "a:b,is:val:id,x:y"                                           | [a: "b", is: "val:id", x: "y"]
    "a:b:c:d"                                                     | [a: "b:c:d"]
    // Invalid strings:
    ""                                                            | [:]
    "1"                                                           | [:]
    "a"                                                           | [:]
    "a,1"                                                         | [:]
    "!a"                                                          | [:]
    "    "                                                        | [:]
    ",,,,"                                                        | [:]
    ":,:,:,:,"                                                    | [:]
    ": : : : "                                                    | [:]
    "::::"                                                        | [:]
    // spotless:on
  }

  def "verify mapping header tags on tracer for #mapString"() {
    setup:
    Map<String, String> rqMap = map.clone()
    rqMap.put("rqh1", "http.request.headers.rqh1")
    System.setProperty(PREFIX + HEADER_TAGS, mapString)
    System.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rqh1")
    System.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsh1")
    Map<String, String> rsMap = map.collectEntries { k, v -> [k, v.replace("http.request.headers", "http.response.headers")] }
    rsMap.put("rsh1", "http.response.headers.rsh1")
    def props = new Properties()
    props.setProperty(HEADER_TAGS, mapString)
    props.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rQh1")
    props.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsH1")

    when:
    def config = new Config()
    def propConfig = Config.get(props)

    then:
    config.requestHeaderTags == rqMap
    propConfig.requestHeaderTags == rqMap
    config.responseHeaderTags == rsMap
    propConfig.responseHeaderTags == rsMap

    where:
    // spotless:off
    mapString                                                     | map
    "a:one, a:two, a:three"                                       | [a: "three"]
    "a:b,c:d,e:"                                                  | [a: "b", c: "d"]
    // space separated
    "a:one  a:two  a:three"                                       | [a: "three"]
    "a:b c:d e:"                                                  | [a: "b", c: "d"]
    // More different string variants:
    "a:"                                                          | [:]
    "a:a;"                                                        | [a: "a;"]
    "a:one, a:two, a:three"                                       | [a: "three"]
    "a:one  a:two  a:three"                                       | [a: "three"]
    "a:b,c:d,e:"                                                  | [a: "b", c: "d"]
    "a:b c:d e:"                                                  | [a: "b", c: "d"]
    "key=1!:va|ue_1,"                                             | ["key=1!": "va|ue_1"]
    "key=1!:va|ue_1 "                                             | ["key=1!": "va|ue_1"]
    " kEy1 :vaLue1 ,\t keY2:  valUe2"                             | [key1: "vaLue1", key2: "valUe2"]
    "a:b,c,D"                                                     | [a: "b", c: "http.request.headers.c", d: "http.request.headers.d"]
    "a:b,C,d,k:v"                                                 | [a: "b", c: "http.request.headers.c", d: "http.request.headers.d", k: "v"]
    "a b c:d "                                                    | [a: "http.request.headers.a", b: "http.request.headers.b", c: "d"]
    "dyno:web.1 dynotype:web buildpackversion:dev appname:n*****" | ["dyno": "web.1", "dynotype": "web", "buildpackversion": "dev", "appname": "n*****"]
    "A.1,B.1"                                                     | ["a.1": "http.request.headers.a_1", "b.1": "http.request.headers.b_1"]
    "is:val:id"                                                   | [is: "val:id"]
    "a:b,is:val:id,x:y"                                           | [a: "b", is: "val:id", x: "y"]
    "a:b:c:d"                                                     | [a: "b:c:d"]
    // Invalid strings:
    ""                                                            | [:]
    "1"                                                           | [:]
    "a:1"                                                         | [:]
    "a,1"                                                         | [:]
    "!a"                                                          | [:]
    "    "                                                        | [:]
    ",,,,"                                                        | [:]
    ":,:,:,:,"                                                    | [:]
    ": : : : "                                                    | [:]
    "::::"                                                        | [:]
    "kEy1 :value1  \t keY2:  value2"                              | [:]
    // spotless:on
  }

  def "verify integer range configs on tracer"() {
    setup:
    System.setProperty(PREFIX + HTTP_SERVER_ERROR_STATUSES, value)
    System.setProperty(PREFIX + HTTP_CLIENT_ERROR_STATUSES, value)
    def props = new Properties()
    props.setProperty(HTTP_CLIENT_ERROR_STATUSES, value)
    props.setProperty(HTTP_SERVER_ERROR_STATUSES, value)

    when:
    def config = new Config()
    def propConfig = Config.get(props)

    then:
    if (expected) {
      assert config.httpServerErrorStatuses == toBitSet(expected)
      assert config.httpClientErrorStatuses == toBitSet(expected)
      assert propConfig.httpServerErrorStatuses == toBitSet(expected)
      assert propConfig.httpClientErrorStatuses == toBitSet(expected)
    } else {
      assert config.httpServerErrorStatuses == DEFAULT_HTTP_SERVER_ERROR_STATUSES
      assert config.httpClientErrorStatuses == DEFAULT_HTTP_CLIENT_ERROR_STATUSES
      assert propConfig.httpServerErrorStatuses == DEFAULT_HTTP_SERVER_ERROR_STATUSES
      assert propConfig.httpClientErrorStatuses == DEFAULT_HTTP_CLIENT_ERROR_STATUSES
    }

    where:
    value               | expected // null means default value
    // spotless:off
    "1"                 | [1]
    "3,13,400-403"      | [3,13,400,401,402,403]
    "2,10,13-15"        | [2,10,13,14,15]
    "a"                 | null
    ""                  | null
    "1000"              | null
    "100-200-300"       | null
    "500"               | [500]
    "100,999"           | [100, 999]
    "999-888"           | 888..999
    "400-403,405-407"   | [400, 401, 402, 403, 405, 406, 407]
    " 400 - 403 , 405 " | [400, 401, 402, 403, 405]
    // spotless:on
  }

  def "verify null value mapping configs on tracer"() {
    setup:
    environmentVariables.set(DD_SERVICE_MAPPING_ENV, mapString)
    environmentVariables.set(DD_SPAN_TAGS_ENV, mapString)
    environmentVariables.set(DD_HEADER_TAGS_ENV, mapString)

    when:
    def config = new Config()

    then:
    config.serviceMapping == map
    config.spanTags == map
    config.requestHeaderTags == map

    where:
    mapString | map
    // spotless:off
    null      | [:]
    ""        | [:]
    // spotless:on
  }

  def "verify empty value list configs on tracer"() {
    setup:
    System.setProperty(PREFIX + JMX_FETCH_METRICS_CONFIGS, listString)

    when:
    def config = new Config()

    then:
    config.jmxFetchMetricsConfigs == list

    where:
    // spotless:off
    listString | list
    ""         | []
    // spotless:on
  }

  def "verify hostname not added to root span tags by default"() {
    setup:
    Properties properties = new Properties()

    when:
    def config = Config.get(properties)

    then:
    !config.localRootSpanTags.containsKey('_dd.hostname')
  }

  def "verify configuration to add hostname to root span tags"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(TRACE_REPORT_HOSTNAME, 'true')

    when:
    def config = Config.get(properties)

    then:
    config.localRootSpanTags.containsKey('_dd.hostname')
  }

  def "verify schema version is added to local root span"() {

    when:
    def config = Config.get()

    then:
    config.localRootSpanTags.get('_dd.trace_span_attribute_schema') == 0
  }

  def "verify fallback to properties file"() {
    setup:
    System.setProperty(PREFIX + CONFIGURATION_FILE, "src/test/resources/dd-java-tracer.properties")

    when:
    def config = new Config()

    then:
    config.configFileStatus == "src/test/resources/dd-java-tracer.properties"
    config.serviceName == "set-in-properties"
  }

  def "verify fallback to properties file has lower priority than system property"() {
    setup:
    System.setProperty(PREFIX + CONFIGURATION_FILE, "src/test/resources/dd-java-tracer.properties")
    System.setProperty(PREFIX + SERVICE_NAME, "set-in-system")

    when:
    def config = new Config()

    then:
    config.configFileStatus == "src/test/resources/dd-java-tracer.properties"
    config.serviceName == "set-in-system"
  }

  def "verify fallback to properties file has lower priority than env var"() {
    setup:
    System.setProperty(PREFIX + CONFIGURATION_FILE, "src/test/resources/dd-java-tracer.properties")
    environmentVariables.set("DD_SERVICE_NAME", "set-in-env")

    when:
    def config = new Config()

    then:
    config.configFileStatus == "src/test/resources/dd-java-tracer.properties"
    config.serviceName == "set-in-env"
  }

  def "verify fallback to DD_SERVICE"() {
    setup:
    environmentVariables.set("DD_SERVICE", "service-name-from-dd-service-env-var")

    when:
    def config = new Config()

    then:
    config.serviceName == "service-name-from-dd-service-env-var"
  }

  def "verify fallback to properties file that does not exist does not crash app"() {
    setup:
    System.setProperty(PREFIX + CONFIGURATION_FILE, "src/test/resources/do-not-exist.properties")

    when:
    def config = new Config()

    then:
    config.serviceName == 'unnamed-java-app'
  }

  def "verify api key loaded from file: #path"() {
    setup:
    environmentVariables.set(DD_API_KEY_ENV, "default-api-key")
    System.setProperty(PREFIX + API_KEY_FILE, path)

    when:
    def config = new Config()

    then:
    config.apiKey == expectedKey

    where:
    // spotless:off
    path                                                        | expectedKey
    getClass().getClassLoader().getResource("apikey").getFile() | "test-api-key"
    "/path/that/doesnt/exist"                                   | "default-api-key"
    // spotless:on
  }

  def "verify api key loaded from old option name"() {
    setup:
    environmentVariables.set(DD_PROFILING_API_KEY_OLD_ENV, "old-api-key")

    when:
    def config = new Config()

    then:
    config.apiKey == "old-api-key"
  }

  def "verify api key loaded from file for old option name: #path"() {
    setup:
    environmentVariables.set(DD_PROFILING_API_KEY_OLD_ENV, "default-api-key")
    System.setProperty(PREFIX + PROFILING_API_KEY_FILE_OLD, path)

    when:
    def config = new Config()

    then:
    config.apiKey == expectedKey

    where:
    // spotless:off
    path                                                            | expectedKey
    getClass().getClassLoader().getResource("apikey.old").getFile() | "test-api-key-old"
    "/path/that/doesnt/exist"                                       | "default-api-key"
    // spotless:on
  }

  def "verify api key loaded from very old option name"() {
    setup:
    environmentVariables.set(DD_PROFILING_API_KEY_VERY_OLD_ENV, "very-old-api-key")

    when:
    def config = new Config()

    then:
    config.apiKey == "very-old-api-key"
  }

  def "verify api key loaded from file for very old option name: #path"() {
    setup:
    environmentVariables.set(DD_PROFILING_API_KEY_VERY_OLD_ENV, "default-api-key")
    System.setProperty(PREFIX + PROFILING_API_KEY_FILE_VERY_OLD, path)

    when:
    def config = new Config()

    then:
    config.apiKey == expectedKey

    where:
    path                                                                 | expectedKey
    getClass().getClassLoader().getResource("apikey.very-old").getFile() | "test-api-key-very-old"
    "/path/that/doesnt/exist"                                            | "default-api-key"
  }

  def "verify api key loaded from new option when both new and old are set"() {
    setup:
    System.setProperty(PREFIX + API_KEY_FILE, getClass().getClassLoader().getResource("apikey").getFile())
    System.setProperty(PREFIX + PROFILING_API_KEY_FILE_OLD, getClass().getClassLoader().getResource("apikey.old").getFile())

    when:
    def config = new Config()

    then:
    config.apiKey == "test-api-key"
  }

  def "verify api key loaded from new option when both old and very old are set"() {
    setup:
    System.setProperty(PREFIX + PROFILING_API_KEY_FILE_OLD, getClass().getClassLoader().getResource("apikey.old").getFile())
    System.setProperty(PREFIX + PROFILING_API_KEY_FILE_VERY_OLD, getClass().getClassLoader().getResource("apikey.very-old").getFile())

    when:
    def config = new Config()

    then:
    config.apiKey == "test-api-key-old"
  }

  def "verify dd.tags overrides global tags in properties"() {
    setup:
    def prop = new Properties()
    prop.setProperty(TAGS, "a:1,env:us-west,version:42")
    prop.setProperty(GLOBAL_TAGS, "b:2")
    prop.setProperty(SPAN_TAGS, "c:3")
    prop.setProperty(JMX_TAGS, "d:4")
    prop.setProperty(HEADER_TAGS, "e:five")
    prop.setProperty(PROFILING_TAGS, "f:6")
    prop.setProperty(ENV, "eu-east")
    prop.setProperty(VERSION, "43")

    when:
    Config config = Config.get(prop)

    then:
    config.mergedSpanTags == [a: "1", b: "2", c: "3", (ENV): "eu-east", (VERSION): "43"]
    config.mergedJmxTags == [a               : "1", b: "2", d: "4", (ENV): "eu-east", (VERSION): "43",
      (RUNTIME_ID_TAG): config.getRuntimeId(), (SERVICE_TAG): config.serviceName]
    config.requestHeaderTags == [e: "five"]

    config.mergedProfilingTags == [a            : "1", b: "2", f: "6", (ENV): "eu-east", (VERSION): "43",
      (HOST_TAG)   : config.getHostName(), (RUNTIME_ID_TAG): config.getRuntimeId(), (RUNTIME_VERSION_TAG): config.getRuntimeVersion(),
      (SERVICE_TAG): config.serviceName, (LANGUAGE_TAG_KEY): LANGUAGE_TAG_VALUE]
  }

  def "verify dd.tags overrides global tags in system properties"() {
    setup:
    System.setProperty(PREFIX + TAGS, "a:1")
    System.setProperty(PREFIX + GLOBAL_TAGS, "b:2")
    System.setProperty(PREFIX + SPAN_TAGS, "c:3")
    System.setProperty(PREFIX + JMX_TAGS, "d:4")
    System.setProperty(PREFIX + HEADER_TAGS, "e:five")
    System.setProperty(PREFIX + PROFILING_TAGS, "f:6")

    when:
    Config config = new Config()

    then:
    config.mergedSpanTags == [a: "1", b: "2", c: "3"]
    config.mergedJmxTags == [a: "1", b: "2", d: "4", (RUNTIME_ID_TAG): config.getRuntimeId(), (SERVICE_TAG): config.serviceName]
    config.requestHeaderTags == [e: "five"]

    config.mergedProfilingTags == [a: "1", b: "2", f: "6", (HOST_TAG): config.getHostName(), (RUNTIME_ID_TAG): config.getRuntimeId(), (RUNTIME_VERSION_TAG): config.getRuntimeVersion(), (SERVICE_TAG): config.serviceName, (LANGUAGE_TAG_KEY): LANGUAGE_TAG_VALUE]
  }

  def "verify dd.tags merges with global tags in env variables"() {
    setup:
    environmentVariables.set(DD_TAGS_ENV, "a:1:2")
    environmentVariables.set(DD_GLOBAL_TAGS_ENV, "b:2")
    environmentVariables.set(DD_SPAN_TAGS_ENV, "c:3")
    environmentVariables.set(DD_JMX_TAGS_ENV, "d:4")
    environmentVariables.set(DD_HEADER_TAGS_ENV, "e:five")
    environmentVariables.set(DD_PROFILING_TAGS_ENV, "f:6")

    when:
    Config config = new Config()

    then:
    config.mergedSpanTags == [a: "1:2", b: "2", c: "3"]
    config.mergedJmxTags == [a: "1:2", b: "2", d: "4", (RUNTIME_ID_TAG): config.getRuntimeId(), (SERVICE_TAG): config.serviceName]
    config.requestHeaderTags == [e: "five"]

    config.mergedProfilingTags == [a: "1:2", b: "2", f: "6", (HOST_TAG): config.getHostName(), (RUNTIME_ID_TAG): config.getRuntimeId(), (RUNTIME_VERSION_TAG): config.getRuntimeVersion(), (SERVICE_TAG): config.serviceName, (LANGUAGE_TAG_KEY): LANGUAGE_TAG_VALUE]
  }

  def "toString works when passwords are empty"() {
    when:
    def config = new Config()

    then:
    config.toString().contains("apiKey=null")
    config.toString().contains("profilingProxyPassword=null")
  }

  def "sensitive information removed for toString/debug log"() {
    setup:
    environmentVariables.set(DD_API_KEY_ENV, "test-secret-api-key")
    environmentVariables.set(DD_PROFILING_PROXY_PASSWORD_ENV, "test-secret-proxy-password")

    when:
    def config = new Config()

    then:
    config.toString().contains("apiKey=****")
    !config.toString().contains("test-secret-api-key")
    config.toString().contains("profilingProxyPassword=****")
    !config.toString().contains("test-secret-proxy-password")
    config.apiKey == "test-secret-api-key"
    config.profilingProxyPassword == "test-secret-proxy-password"
  }

  def "custom datadog site with agentless profiling"() {
    setup:
    def prop = new Properties()
    prop.setProperty(SITE, "some.new.site")
    prop.setProperty(PROFILING_AGENTLESS, "true")

    when:
    Config config = Config.get(prop)

    then:
    config.getFinalProfilingUrl() == "https://intake.profile.some.new.site/api/v2/profile"
  }

  def "custom datadog site without agentless profiling"() {
    setup:
    def prop = new Properties()
    prop.setProperty(SITE, "some.new.site")

    when:
    Config config = Config.get(prop)

    then:
    config.getFinalProfilingUrl() == "http://" + config.getAgentHost() + ":" + config.getAgentPort() + "/profiling/v1/input"
  }

  def "presence of api key does not lead to agentless profiling"() {
    setup:
    def prop = new Properties()
    prop.setProperty(API_KEY, "some.api.key")

    when:
    Config config = Config.get(prop)

    then:
    config.getFinalProfilingUrl() == "http://" + config.getAgentHost() + ":" + config.getAgentPort() + "/profiling/v1/input"
  }

  def "custom profiling url override"() {
    setup:
    def prop = new Properties()
    prop.setProperty(SITE, "some.new.site")
    prop.setProperty(PROFILING_URL, "https://some.new.url/goes/here")

    when:
    Config config = Config.get(prop)

    then:
    config.getFinalProfilingUrl() == "https://some.new.url/goes/here"
  }

  def "ipv6 profiling url"() {
    setup:
    def configuredUrl = "http://[2600:1f14:1cfc:5f07::38d4]:8126"
    def props = new Properties()
    props.setProperty(TRACE_AGENT_URL, configuredUrl)

    when:
    Config config = Config.get(props)

    then:
    config.getFinalProfilingUrl() == configuredUrl + "/profiling/v1/input"
  }

  def "uds profiling url"() {
    setup:
    def configuredUrl = "unix:///path/to/socket"
    def props = new Properties()
    props.setProperty(TRACE_AGENT_URL, configuredUrl)

    when:
    Config config = Config.get(props)

    then:
    config.getFinalProfilingUrl() == "http://" + config.getAgentHost() + ":" + config.getAgentPort() + "/profiling/v1/input"
  }

  def "fallback to DD_TAGS"() {
    setup:
    environmentVariables.set(DD_TAGS_ENV, "a:1,b:2,c:3")

    when:
    Config config = new Config()

    then:
    config.mergedSpanTags == [a: "1", c: "3", b: "2"]
  }

  def "explicit DD_ENV and DD_VERSION overwrite DD_TAGS"() {
    setup:
    environmentVariables.set(DD_TAGS_ENV, "env:production   ,    version:3.2.1")
    environmentVariables.set(DD_ENV_ENV, "test_env")
    environmentVariables.set(DD_VERSION_ENV, "1.2.3")

    when:
    Config config = new Config()

    then:
    config.mergedSpanTags == ["env": "test_env", "version": "1.2.3"]
    config.getWellKnownTags().getEnv() as String == "test_env"
    config.getWellKnownTags().getVersion() as String == "1.2.3"
  }

  def "explicit DD_ENV and DD_VERSION overwrites dd.trace.global.tags"() {
    setup:
    environmentVariables.set(DD_VERSION_ENV, "1.2.3")
    environmentVariables.set(DD_ENV_ENV, "production-us")
    System.setProperty(PREFIX + GLOBAL_TAGS,
      "env:us-barista-test,other_tag:test,version:3.2.1")

    when:
    Config config = new Config()

    then:
    config.mergedSpanTags == ["version": "1.2.3", "env": "production-us", "other_tag": "test"]
    config.getWellKnownTags().getEnv() as String == "production-us"
    config.getWellKnownTags().getVersion() as String == "1.2.3"
  }

  def "merge env from dd.trace.global.tags and DD_VERSION"() {
    setup:
    environmentVariables.set(DD_VERSION_ENV, "1.2.3")
    System.setProperty(PREFIX + GLOBAL_TAGS, "env:us-barista-test,other_tag:test,version:3.2.1")

    when:
    Config config = new Config()

    then:
    config.mergedSpanTags == ["version": "1.2.3", "env": "us-barista-test", "other_tag": "test"]
  }

  def "merge version from dd.trace.global.tags and DD_ENV"() {
    setup:
    environmentVariables.set(DD_ENV_ENV, "us-barista-test")
    System.setProperty(PREFIX + GLOBAL_TAGS, "other_tag:test,version:3.2.1")

    when:
    Config config = new Config()

    then:
    config.mergedSpanTags == ["version": "3.2.1", "env": "us-barista-test", "other_tag": "test"]
  }

  def "merge version from dd.trace.global.tags and DD_SERVICE and DD_ENV"() {
    setup:
    environmentVariables.set("DD_SERVICE", "dd-service-env-var")
    environmentVariables.set(DD_ENV_ENV, "us-barista-test")
    System.setProperty(PREFIX + GLOBAL_TAGS, "other_tag:test,version:3.2.1,service.version:my-svc-vers")

    when:
    Config config = new Config()

    then:
    config.serviceName == "dd-service-env-var"
    config.mergedSpanTags == [version: "3.2.1", "service.version": "my-svc-vers", "env": "us-barista-test", other_tag: "test"]
    config.mergedJmxTags == [(RUNTIME_ID_TAG): config.getRuntimeId(), (SERVICE_TAG): 'dd-service-env-var',
      version         : "3.2.1", "service.version": "my-svc-vers", "env": "us-barista-test", other_tag: "test"]
  }

  def "merge env from dd.trace.global.tags and DD_SERVICE and DD_VERSION"() {
    setup:
    environmentVariables.set("DD_SERVICE", "dd-service-env-var")
    environmentVariables.set(DD_VERSION_ENV, "3.2.1")
    System.setProperty(PREFIX + GLOBAL_TAGS, "other_tag:test,env:us-barista-test,service.version:my-svc-vers")

    when:
    Config config = new Config()

    then:
    config.serviceName == "dd-service-env-var"
    config.mergedSpanTags == [version: "3.2.1", "service.version": "my-svc-vers", "env": "us-barista-test", other_tag: "test"]
    config.mergedJmxTags == [(RUNTIME_ID_TAG): config.getRuntimeId(), (SERVICE_TAG): 'dd-service-env-var',
      version         : "3.2.1", "service.version": "my-svc-vers", "env": "us-barista-test", other_tag: "test"]
  }

  def "set of dd.trace.global.tags.env exclusively by java properties and without DD_ENV"() {
    setup:
    System.setProperty(PREFIX + GLOBAL_TAGS, "env:production")

    when:
    Config config = new Config()

    then:
    //check that env wasn't set:
    environmentVariables.get(DD_ENV_ENV) == null
    environmentVariables.get(DD_VERSION_ENV) == null
    //actual guard:
    config.mergedSpanTags == ["env": "production"]
  }

  def "set of dd.trace.global.tags.version exclusively by java properties"() {
    setup:
    System.setProperty(PREFIX + GLOBAL_TAGS, "version:42")

    when:
    Config config = new Config()

    then:
    //check that env wasn't set:
    environmentVariables.get(DD_ENV_ENV) == null
    environmentVariables.get(DD_VERSION_ENV) == null
    //actual guard:
    config.mergedSpanTags == [(VERSION): "42"]
  }

  def "set of version exclusively by DD_VERSION and without DD_ENV "() {
    setup:
    environmentVariables.set(DD_VERSION_ENV, "3.2.1")

    when:
    Config config = new Config()

    then:
    environmentVariables.get(DD_ENV_ENV) == null
    config.mergedSpanTags.get("env") == null
    config.mergedSpanTags == [(VERSION): "3.2.1"]
  }

  // service name precedence checks
  def "default service name exist"() {
    expect:
    Config.get().serviceName == DEFAULT_SERVICE_NAME
  }

  def "default service name is not affected by tags, nor env variables"() {
    setup:
    System.setProperty(PREFIX + GLOBAL_TAGS, "service:service-tag-in-dd-trace-global-tags-java-property,service.version:my-svc-vers")

    when:
    def config = new Config()

    then:
    config.serviceName == DEFAULT_SERVICE_NAME
    config.mergedSpanTags == [service: 'service-tag-in-dd-trace-global-tags-java-property', 'service.version': 'my-svc-vers']
    config.mergedJmxTags == [(RUNTIME_ID_TAG) : config.getRuntimeId(), (SERVICE_TAG): config.serviceName,
      'service.version': 'my-svc-vers']
  }

  def "service name prioritizes values from DD_SERVICE over tags"() {
    setup:
    System.setProperty(PREFIX + TAGS, "service:service-name-from-tags")
    System.setProperty(PREFIX + SERVICE, "service-name-from-dd-service")

    when:
    def config = new Config()

    then:
    config.serviceName == "service-name-from-dd-service"
    !config.mergedSpanTags.containsKey("service")
  }

  def "DD_SERVICE precedence over 'dd.service.name' java property is set; 'dd.service' overwrites DD_SERVICE"() {
    setup:
    environmentVariables.set(DD_SERVICE_NAME_ENV, "dd-service-name-env-var")
    System.setProperty(PREFIX + SERVICE_NAME, "dd-service-name-java-prop")
    environmentVariables.set("DD_SERVICE", "dd-service-env-var")
    System.setProperty(PREFIX + SERVICE, "dd-service-java-prop")
    System.setProperty(PREFIX + GLOBAL_TAGS, "service:service-tag-in-dd-trace-global-tags-java-property,service.version:my-svc-vers")

    when:
    def config = new Config()

    then:
    config.serviceName == "dd-service-java-prop"
    config.mergedSpanTags == ['service.version': 'my-svc-vers']
    config.mergedJmxTags == [(RUNTIME_ID_TAG) : config.getRuntimeId(), (SERVICE_TAG): config.serviceName,
      'service.version': 'my-svc-vers']
  }

  def "DD_SERVICE precedence over 'DD_SERVICE_NAME' environment var is set"() {
    setup:
    environmentVariables.set(DD_SERVICE_NAME_ENV, "dd-service-name-env-var")
    environmentVariables.set("DD_SERVICE", "dd-service-env-var")
    System.setProperty(PREFIX + GLOBAL_TAGS, "service:service-tag-in-dd-trace-global-tags-java-property,service.version:my-svc-vers")

    when:
    def config = new Config()

    then:
    config.serviceName == "dd-service-env-var"
    config.mergedSpanTags == ['service.version': 'my-svc-vers']
    config.mergedJmxTags == [(RUNTIME_ID_TAG) : config.getRuntimeId(), (SERVICE_TAG): config.serviceName,
      'service.version': 'my-svc-vers']
  }

  def "dd.service overwrites DD_SERVICE"() {
    setup:
    environmentVariables.set("DD_SERVICE", "dd-service-env-var")
    System.setProperty(PREFIX + SERVICE, "dd-service-java-prop")
    System.setProperty(PREFIX + GLOBAL_TAGS, "service:service-tag-in-dd-trace-global-tags-java-property,service.version:my-svc-vers")

    when:
    def config = new Config()

    then:
    config.serviceName == "dd-service-java-prop"
    config.mergedSpanTags == ['service.version': 'my-svc-vers']
    config.mergedJmxTags == [(RUNTIME_ID_TAG) : config.getRuntimeId(), (SERVICE_TAG): config.serviceName,
      'service.version': 'my-svc-vers']
  }

  def "set servicename by DD_SERVICE"() {
    setup:
    environmentVariables.set("DD_SERVICE", "dd-service-env-var")
    System.setProperty(PREFIX + GLOBAL_TAGS, "service:service-tag-in-dd-trace-global-tags-java-property,service.version:my-svc-vers")
    environmentVariables.set(DD_GLOBAL_TAGS_ENV, "service:service-tag-in-env-var,service.version:my-svc-vers")

    when:
    def config = new Config()

    then:
    config.serviceName == "dd-service-env-var"
    config.mergedSpanTags == ['service.version': 'my-svc-vers']
    config.mergedJmxTags == [(RUNTIME_ID_TAG) : config.getRuntimeId(), (SERVICE_TAG): config.serviceName,
      'service.version': 'my-svc-vers']
  }

  def "explicit service name is not overridden by captured environment"() {
    setup:
    System.setProperty(PREFIX + serviceProperty, serviceName)

    when:
    def config = new Config()

    then:
    config.serviceName == serviceName
    assert config.isServiceNameSetByUser()

    where:
    [serviceProperty, serviceName] << [[SERVICE, SERVICE_NAME], [DEFAULT_SERVICE_NAME, "my-service"]].combinations()
  }

  def "verify behavior of features under DD_TRACE_EXPERIMENTAL_FEATURES_ENABLED"() {
    setup:
    environmentVariables.set("DD_TRACE_EXPERIMENTAL_FEATURES_ENABLED", "DD_LOGS_INJECTION, DD_TAGS")
    environmentVariables.set("DD_TAGS", "env:test,aKey:aVal bKey:bVal cKey:")

    when:
    def config = new Config()

    then:
    config.experimentalFeaturesEnabled == ["DD_LOGS_INJECTION", "DD_TAGS"].toSet()

    //verify expected behavior enabled under feature flag
    config.logsInjectionEnabled == false
    config.globalTags == [env: "test", aKey: "aVal bKey:bVal cKey:"]
  }

  def "verify behavior of 'breaking change' configs when not under DD_TRACE_EXPERIMENTAL_FEATURES_ENABLED"() {
    setup:
    environmentVariables.set("DD_TAGS", "env:test,aKey:aVal bKey:bVal cKey:")

    when:
    def config = new Config()

    then:
    config.experimentalFeaturesEnabled == [].toSet()

    //verify expected behavior when not enabled under feature flag
    config.logsInjectionEnabled == true
    config.globalTags == [env:"test", aKey:"aVal", bKey:"bVal"]
  }

  def "verify behavior of DD_TRACE_EXPERIMENTAL_FEATURE_ENABLED when value is 'all'"() {
    setup:
    environmentVariables.set("DD_TRACE_EXPERIMENTAL_FEATURES_ENABLED", "all")

    when:
    def config = new Config()

    then:
    config.experimentalFeaturesEnabled == ["DD_TAGS", "DD_LOGS_INJECTION", "DD_EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED"].toSet()
  }

  def "detect if agent is configured using default values"() {
    setup:
    if (host != null) {
      System.setProperty(PREFIX + AGENT_HOST, host)
    }
    if (socket != null) {
      System.setProperty(PREFIX + AGENT_UNIX_DOMAIN_SOCKET, socket)
    }
    if (port != null) {
      System.setProperty(PREFIX + TRACE_AGENT_PORT, port)
    }
    if (legacyPort != null) {
      System.setProperty(PREFIX + AGENT_PORT_LEGACY, legacyPort)
    }

    when:
    def config = new Config()

    then:
    config.isAgentConfiguredUsingDefault() == configuredUsingDefault

    when:
    Properties properties = new Properties()
    if (propertyHost != null) {
      properties.setProperty(AGENT_HOST, propertyHost)
    }
    if (propertySocket != null) {
      properties.setProperty(AGENT_UNIX_DOMAIN_SOCKET, propertySocket)
    }
    if (propertyPort != null) {
      properties.setProperty(TRACE_AGENT_PORT, propertyPort)
    }

    def childConfig = new Config(ConfigProvider.withPropertiesOverride(properties))

    then:
    childConfig.isAgentConfiguredUsingDefault() == childConfiguredUsingDefault

    where:
    // spotless:off
    host                              | socket    | port | legacyPort | propertyHost | propertySocket | propertyPort | configuredUsingDefault | childConfiguredUsingDefault
    null                              | null      | null | null       | null         | null           | null         | true                   | true
    "example"                         | null      | null | null       | null         | null           | null         | false                  | false
    ConfigDefaults.DEFAULT_AGENT_HOST | null      | null | null       | null         | null           | null         | false                  | false
    null                              | "example" | null | null       | null         | null           | null         | false                  | false
    null                              | null      | "1"  | null       | null         | null           | null         | false                  | false
    null                              | null      | null | "1"        | null         | null           | null         | false                  | false
    "example"                         | "example" | null | null       | null         | null           | null         | false                  | false
    null                              | null      | null | null       | "example"    | null           | null         | true                   | false
    null                              | null      | null | null       | null         | "example"      | null         | true                   | false
    null                              | null      | null | null       | null         | null           | "1"          | true                   | false
    "example"                         | "example" | null | null       | "example"    | null           | null         | false                  | false
    // spotless:on
  }

  def "valueOf positive test"() {
    expect:
    ConfigConverter.valueOf(value, tClass) == expected

    where:
    // spotless:off
    value       | tClass  | expected
    "true"      | Boolean | true
    "trUe"      | Boolean | true
    "false"     | Boolean | false
    "False"     | Boolean | false
    "1"         | Boolean | true
    "0"         | Boolean | false
    "42.42"     | Float   | 42.42f
    "42.42"     | Double  | 42.42
    "44"        | Integer | 44
    "45"        | Long    | 45
    "46"        | Short   | 46
    // spotless:on
  }

  def "valueOf negative test when tClass is null"() {
    when:
    ConfigConverter.valueOf(value, null)

    then:
    def exception = thrown(NullPointerException)
    exception.message == "tClass is marked non-null but is null"

    where:
    value    | defaultValue
    // spotless:off
    null     | "42"
    ""       | "43"
    "      " | "44"
    "1"      | "45"
    // spotless:on
  }

  def "valueOf negative test for invalid boolean values"() {
    when:
    ConfigConverter.valueOf(value, Boolean)

    then:
    def exception = thrown(IllegalArgumentException)
    exception.message.contains("Invalid boolean value")

    where:
    // spotless:off
    value << ["42.42", "tru", "truee", "true ", " true", " true ", "   true  ", "notABool", "invalid", "yes", "no", "42"]
    // spotless:on
  }

  def "valueOf negative test"() {
    when:
    ConfigConverter.valueOf(value, tClass)

    then:
    def exception = thrown(NumberFormatException)
    println("cause: ": exception.message)

    where:
    // spotless:off
    value   | tClass
    "42.42" | Number
    "42.42" | Byte
    "42.42" | Character
    "42.42" | Short
    "42.42" | Integer
    "42.42" | Long
    "42.42" | Object
    "42.42" | Object[]
    "42.42" | boolean[]
    "42.42" | boolean
    "42.42" | byte
    "42.42" | byte
    "42.42" | char
    "42.42" | short
    "42.42" | int
    "42.42" | long
    "42.42" | double
    "42.42" | float
    "42.42" | ClassThrowsExceptionForValueOfMethod // will wrapped in NumberFormatException anyway
    // spotless:on
  }

  def "revert to RANDOM with invalid id generation strategy"() {
    setup:
    def prop = new Properties()
    prop.setProperty(ID_GENERATION_STRATEGY, "LOL")
    when:
    Config config = Config.get(prop)

    then:
    config.idGenerationStrategy.class.name.endsWith('$Random')
  }

  def "DD_RUNTIME_METRICS_ENABLED=false disables all metrics"() {
    setup:
    environmentVariables.set(DD_RUNTIME_METRICS_ENABLED_ENV, "false")
    def prop = new Properties()
    prop.setProperty(JMX_FETCH_ENABLED, "true")
    prop.setProperty(HEALTH_METRICS_ENABLED, "true")
    prop.setProperty(PERF_METRICS_ENABLED, "true")

    when:
    Config config = Config.get(prop)

    then:
    !config.jmxFetchEnabled
    !config.healthMetricsEnabled
    !config.perfMetricsEnabled
  }

  def "trace_agent_url overrides default host and port or unix domain"() {
    setup:
    if (configuredUrl != null) {
      System.setProperty(PREFIX + TRACE_AGENT_URL, configuredUrl)
    } else {
      System.clearProperty(PREFIX + TRACE_AGENT_URL)
    }

    when:
    def config = new Config()

    then:
    config.agentUrl == expectedUrl
    config.agentHost == expectedHost
    config.agentPort == expectedPort
    config.agentUnixDomainSocket == expectedUnixDomainSocket

    where:
    // spotless:off
    configuredUrl                     | expectedUrl                       | expectedHost | expectedPort | expectedUnixDomainSocket
    null                              | "http://localhost:8126"           | "localhost"  | 8126         | null
    ""                                | "http://localhost:8126"           | "localhost"  | 8126         | null
    "http://localhost:1234"           | "http://localhost:1234"           | "localhost"  | 1234         | null
    "http://somehost"                 | "http://somehost:8126"            | "somehost"   | 8126         | null
    "http://somehost:80"              | "http://somehost:80"              | "somehost"   | 80           | null
    "https://somehost:8143"           | "https://somehost:8143"           | "somehost"   | 8143         | null
    "unix:///another/socket/path"     | "unix:///another/socket/path"     | "localhost"  | 8126         | "/another/socket/path"
    "unix:///another%2Fsocket%2Fpath" | "unix:///another%2Fsocket%2Fpath" | "localhost"  | 8126         | "/another/socket/path"
    "http:"                           | "http://localhost:8126"           | "localhost"  | 8126         | null
    "unix:"                           | "http://localhost:8126"           | "localhost"  | 8126         | null
    "1234"                            | "http://localhost:8126"           | "localhost"  | 8126         | null
    ":1234"                           | "http://localhost:8126"           | "localhost"  | 8126         | null
    // spotless:on
  }

  def "trace_agent_url overrides configured host and port or unix domain"() {
    setup:
    System.setProperty(PREFIX + AGENT_HOST, "test-host")
    System.setProperty(PREFIX + TRACE_AGENT_PORT, "8888")
    System.setProperty(PREFIX + AGENT_UNIX_DOMAIN_SOCKET, "/path/to/socket")
    if (configuredUrl != null) {
      System.setProperty(PREFIX + TRACE_AGENT_URL, configuredUrl)
    } else {
      System.clearProperty(PREFIX + TRACE_AGENT_URL)
    }

    when:
    def config = new Config()

    then:
    config.agentUrl == expectedUrl
    config.agentHost == expectedHost
    config.agentPort == expectedPort
    config.agentUnixDomainSocket == expectedUnixDomainSocket

    where:
    // spotless:off
    configuredUrl                     | expectedUrl                       | expectedHost | expectedPort | expectedUnixDomainSocket
    null                              | "http://test-host:8888"           | "test-host"  | 8888         | "/path/to/socket"
    ""                                | "http://test-host:8888"           | "test-host"  | 8888         | "/path/to/socket"
    "http://localhost:1234"           | "http://localhost:1234"           | "localhost"  | 1234         | "/path/to/socket"
    "http://somehost"                 | "http://somehost:8888"            | "somehost"   | 8888         | "/path/to/socket"
    "http://somehost:80"              | "http://somehost:80"              | "somehost"   | 80           | "/path/to/socket"
    "https://somehost:8143"           | "https://somehost:8143"           | "somehost"   | 8143         | "/path/to/socket"
    "unix:///another/socket/path"     | "unix:///another/socket/path"     | "localhost"  | 8126         | "/another/socket/path"
    "unix:///another%2Fsocket%2Fpath" | "unix:///another%2Fsocket%2Fpath" | "localhost"  | 8126         | "/another/socket/path"
    "http:"                           | "http://test-host:8888"           | "test-host"  | 8888         | "/path/to/socket"
    "unix:"                           | "http://test-host:8888"           | "test-host"  | 8888         | "/path/to/socket"
    "1234"                            | "http://test-host:8888"           | "test-host"  | 8888         | "/path/to/socket"
    ":1234"                           | "http://test-host:8888"           | "test-host"  | 8888         | "/path/to/socket"
    // spotless:on
  }

  def "test get ignored resource names"() {
    setup:
    System.setProperty(PREFIX + TRACER_METRICS_IGNORED_RESOURCES, "GET /healthcheck,SELECT foo from bar")

    when:
    def config = new Config()
    then:
    config.getMetricsIgnoredResources() == ["GET /healthcheck", "SELECT foo from bar"].toSet()
  }

  def "appsec state with sys = #sys env = #env"() {
    setup:
    if (sys != null) {
      System.setProperty("dd.appsec.enabled", sys)
    }
    if (env != null) {
      environmentVariables.set("DD_APPSEC_ENABLED", env)
    }

    when:
    def config = new Config()

    then:
    config.getAppSecActivation() == res

    where:
    sys        | env        | res
    null       | null       | ProductActivation.ENABLED_INACTIVE
    null       | ""         | ProductActivation.ENABLED_INACTIVE
    null       | "inactive" | ProductActivation.ENABLED_INACTIVE
    null       | "false"    | ProductActivation.FULLY_DISABLED
    null       | "0"        | ProductActivation.FULLY_DISABLED
    null       | "invalid"  | ProductActivation.FULLY_DISABLED
    null       | "true"     | ProductActivation.FULLY_ENABLED
    null       | "1"        | ProductActivation.FULLY_ENABLED
    ""         | null       | ProductActivation.ENABLED_INACTIVE
    ""         | ""         | ProductActivation.ENABLED_INACTIVE
    ""         | "inactive" | ProductActivation.ENABLED_INACTIVE
    ""         | "false"    | ProductActivation.FULLY_DISABLED
    ""         | "0"        | ProductActivation.FULLY_DISABLED
    ""         | "invalid"  | ProductActivation.FULLY_DISABLED
    ""         | "true"     | ProductActivation.FULLY_ENABLED
    ""         | "1"        | ProductActivation.FULLY_ENABLED
    "inactive" | null       | ProductActivation.ENABLED_INACTIVE
    "inactive" | ""         | ProductActivation.ENABLED_INACTIVE
    "inactive" | "inactive" | ProductActivation.ENABLED_INACTIVE
    "inactive" | "false"    | ProductActivation.ENABLED_INACTIVE
    "inactive" | "0"        | ProductActivation.ENABLED_INACTIVE
    "inactive" | "invalid"  | ProductActivation.ENABLED_INACTIVE
    "inactive" | "true"     | ProductActivation.ENABLED_INACTIVE
    "inactive" | "1"        | ProductActivation.ENABLED_INACTIVE
    "false"    | null       | ProductActivation.FULLY_DISABLED
    "false"    | ""         | ProductActivation.FULLY_DISABLED
    "false"    | "inactive" | ProductActivation.FULLY_DISABLED
    "false"    | "false"    | ProductActivation.FULLY_DISABLED
    "false"    | "0"        | ProductActivation.FULLY_DISABLED
    "false"    | "invalid"  | ProductActivation.FULLY_DISABLED
    "false"    | "true"     | ProductActivation.FULLY_DISABLED
    "false"    | "1"        | ProductActivation.FULLY_DISABLED
    "0"        | null       | ProductActivation.FULLY_DISABLED
    "true"     | null       | ProductActivation.FULLY_ENABLED
    "true"     | ""         | ProductActivation.FULLY_ENABLED
    "true"     | "inactive" | ProductActivation.FULLY_ENABLED
    "true"     | "false"    | ProductActivation.FULLY_ENABLED
    "true"     | "0"        | ProductActivation.FULLY_ENABLED
    "true"     | "invalid"  | ProductActivation.FULLY_ENABLED
    "true"     | "true"     | ProductActivation.FULLY_ENABLED
    "true"     | "1"        | ProductActivation.FULLY_ENABLED
    "1"        | null       | ProductActivation.FULLY_ENABLED
  }

  def "hostname discovery with environment variables"() {
    setup:
    final expectedHostname = "myhostname"
    environmentVariables.set("HOSTNAME", expectedHostname)
    environmentVariables.set("COMPUTERNAME", expectedHostname)

    when:
    def hostname = Config.initHostName()

    then:
    hostname == expectedHostname
  }

  def "hostname discovery without environment variables"() {
    setup:
    environmentVariables.set("HOSTNAME", "")
    environmentVariables.set("COMPUTERNAME", "")

    when:
    def hostname = Config.initHostName()

    then:
    hostname != null
    !hostname.trim().isEmpty()
  }

  def "config instantiation should NOT fail if llm obs is enabled via sys prop and ml app is not set"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(LLMOBS_ENABLED, "true")
    properties.setProperty(SERVICE, "test-service")

    when:
    def config = new Config(ConfigProvider.withPropertiesOverride(properties))

    then:
    noExceptionThrown()
    config.isLlmObsEnabled()
    config.llmObsMlApp == "test-service"
  }

  def "config instantiation should NOT fail if llm obs is enabled via sys prop and ml app is empty"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(LLMOBS_ENABLED, "true")
    properties.setProperty(SERVICE, "test-service")
    properties.setProperty(LLMOBS_ML_APP, "")

    when:
    def config = new Config(ConfigProvider.withPropertiesOverride(properties))

    then:
    noExceptionThrown()
    config.isLlmObsEnabled()
    config.llmObsMlApp == "test-service"
  }

  def "config instantiation should NOT fail if llm obs is enabled via env var and ml app is not set"() {
    setup:
    environmentVariables.set(DD_LLMOBS_ENABLED_ENV, "true")
    environmentVariables.set(DD_SERVICE_NAME_ENV, "test-service")

    when:
    def config = new Config()

    then:
    noExceptionThrown()
    config.isLlmObsEnabled()
    config.llmObsMlApp == "test-service"
  }

  def "config instantiation should NOT fail if llm obs is enabled via env var and ml app is empty"() {
    setup:
    environmentVariables.set(DD_LLMOBS_ENABLED_ENV, "true")
    environmentVariables.set(DD_SERVICE_NAME_ENV, "test-service")
    environmentVariables.set(DD_LLMOBS_ML_APP_ENV, "")

    when:
    def config = new Config()

    then:
    noExceptionThrown()
    config.isLlmObsEnabled()
    config.llmObsMlApp == "test-service"
  }


  def "config instantiation should NOT fail if llm obs is enabled (agentless disabled) via sys prop and ml app is set"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(LLMOBS_ENABLED, "true")
    properties.setProperty(LLMOBS_AGENTLESS_ENABLED, "false")
    properties.setProperty(LLMOBS_ML_APP, "test-ml-app")

    when:
    def config = new Config(ConfigProvider.withPropertiesOverride(properties))

    then:
    noExceptionThrown()
    config.isLlmObsEnabled()
    !config.isLlmObsAgentlessEnabled()
    config.llmObsMlApp == "test-ml-app"
  }

  def "config instantiation should NOT fail if llm obs is enabled (agentless disabled) via env var and ml app is set"() {
    setup:
    environmentVariables.set(DD_LLMOBS_ENABLED_ENV, "true")
    environmentVariables.set(DD_LLMOBS_ML_APP_ENV, "test-ml-app")

    when:
    def config = new Config()

    then:
    noExceptionThrown()
    config.isLlmObsEnabled()
    !config.isLlmObsAgentlessEnabled()
    config.llmObsMlApp == "test-ml-app"
  }

  def "config instantiation should fail if llm obs is in agentless mode via sys prop and API key is not set"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(LLMOBS_ENABLED, "true")
    properties.setProperty(LLMOBS_AGENTLESS_ENABLED, "true")
    properties.setProperty(LLMOBS_ML_APP, "test-ml-app")

    when:
    new Config(ConfigProvider.withPropertiesOverride(properties))

    then:
    thrown FatalAgentMisconfigurationError
  }

  def "config instantiation should fail if llm obs is in agentless mode via env var and API key is not set"() {
    setup:
    environmentVariables.set(DD_LLMOBS_ENABLED_ENV, "true")
    environmentVariables.set(DD_LLMOBS_ML_APP_ENV, "a")
    environmentVariables.set(DD_LLMOBS_AGENTLESS_ENABLED_ENV, "true")

    when:
    new Config()

    then:
    thrown FatalAgentMisconfigurationError
  }

  def "config instantiation should NOT fail if llm obs is enabled (agentless enabled) and API key & ml app are set via sys prop"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(LLMOBS_ENABLED, "true")
    properties.setProperty(LLMOBS_AGENTLESS_ENABLED, "true")
    properties.setProperty(LLMOBS_ML_APP, "test-ml-app")
    properties.setProperty(API_KEY, "123456789")

    when:
    def config = new Config(ConfigProvider.withPropertiesOverride(properties))

    then:
    noExceptionThrown()
    config.isLlmObsEnabled()
    config.isLlmObsAgentlessEnabled()
    config.llmObsMlApp == "test-ml-app"
  }

  def "config instantiation should NOT fail if llm obs is enabled (agentless enabled) and API key & ml app are set via env var"() {
    setup:
    environmentVariables.set(DD_LLMOBS_ENABLED_ENV, "true")
    environmentVariables.set(DD_LLMOBS_ML_APP_ENV, "a")
    environmentVariables.set(DD_LLMOBS_AGENTLESS_ENABLED_ENV, "true")
    environmentVariables.set(DD_API_KEY_ENV, "8663294466")

    when:
    def config = new Config()

    then:
    noExceptionThrown()
    config.isLlmObsEnabled()
    config.isLlmObsAgentlessEnabled()
    config.llmObsMlApp == "a"
  }

  def "config instantiation should fail if CI visibility agentless mode is enabled and API key is not set"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(CIVISIBILITY_ENABLED, "true")
    properties.setProperty(CIVISIBILITY_AGENTLESS_ENABLED, "true")

    when:
    new Config(ConfigProvider.withPropertiesOverride(properties))

    then:
    thrown FatalAgentMisconfigurationError
  }

  def "config instantiation should NOT fail if CI visibility agentless mode is enabled and API key is set"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(CIVISIBILITY_ENABLED, "true")
    properties.setProperty(CIVISIBILITY_AGENTLESS_ENABLED, "true")
    properties.setProperty(API_KEY, "123456789")

    when:
    def config = new Config(ConfigProvider.withPropertiesOverride(properties))

    then:
    noExceptionThrown()
    config.isCiVisibilityEnabled()
    config.isCiVisibilityAgentlessEnabled()
  }

  static class ClassThrowsExceptionForValueOfMethod {
    static ClassThrowsExceptionForValueOfMethod valueOf(String ignored) {
      throw new Throwable()
    }
  }

  static BitSet toBitSet(Collection<Integer> set) {
    BitSet bs = new BitSet()
    for (Integer i : set) {
      bs.set(i)
    }
    return bs
  }

  def "check trace propagation style overrides for"() {
    setup:
    if (pse) {
      environmentVariables.set('DD_PROPAGATION_STYLE_EXTRACT', pse.toString())
    }
    if (psi) {
      environmentVariables.set('DD_PROPAGATION_STYLE_INJECT', psi.toString())
    }
    if (tps) {
      environmentVariables.set('DD_TRACE_PROPAGATION_STYLE', tps.toString())
    }
    if (tpse) {
      environmentVariables.set('DD_TRACE_PROPAGATION_STYLE_EXTRACT', tpse.toString())
    }
    if (tpsi) {
      environmentVariables.set('DD_TRACE_PROPAGATION_STYLE_INJECT', tpsi.toString())
    }
    when:
    Config config = new Config()

    then:
    config.propagationStylesToExtract.asList() == ePSE
    config.propagationStylesToInject.asList() == ePSI
    config.tracePropagationStylesToExtract.asList() == eTPSE
    config.tracePropagationStylesToInject.asList() == eTPSI

    where:
    // spotless:off
    pse                      | psi                      | tps      | tpse               | tpsi    | ePSE                       | ePSI                       | eTPSE                            | eTPSI
    PropagationStyle.DATADOG | PropagationStyle.B3      | null     | null               | null    | [PropagationStyle.DATADOG] | [PropagationStyle.B3]      | [DATADOG]                        | [B3SINGLE, B3MULTI]
    PropagationStyle.B3      | PropagationStyle.DATADOG | null     | null               | null    | [PropagationStyle.B3]      | [PropagationStyle.DATADOG] | [B3SINGLE, B3MULTI]              | [DATADOG]
    PropagationStyle.B3      | PropagationStyle.DATADOG | HAYSTACK | null               | null    | [PropagationStyle.B3]      | [PropagationStyle.DATADOG] | [HAYSTACK]                       | [HAYSTACK]
    PropagationStyle.B3      | PropagationStyle.DATADOG | HAYSTACK | B3SINGLE           | null    | [PropagationStyle.B3]      | [PropagationStyle.DATADOG] | [B3SINGLE]                       | [HAYSTACK]
    PropagationStyle.B3      | PropagationStyle.DATADOG | HAYSTACK | null               | B3MULTI | [PropagationStyle.B3]      | [PropagationStyle.DATADOG] | [HAYSTACK]                       | [B3MULTI]
    PropagationStyle.B3      | PropagationStyle.DATADOG | HAYSTACK | B3SINGLE           | B3MULTI | [PropagationStyle.B3]      | [PropagationStyle.DATADOG] | [B3SINGLE]                       | [B3MULTI]
    PropagationStyle.B3      | PropagationStyle.DATADOG | null     | B3SINGLE           | B3MULTI | [PropagationStyle.B3]      | [PropagationStyle.DATADOG] | [B3SINGLE]                       | [B3MULTI]
    null                     | null                     | HAYSTACK | null               | null    | [PropagationStyle.DATADOG] | [PropagationStyle.DATADOG] | [HAYSTACK]                       | [HAYSTACK]
    null                     | null                     | HAYSTACK | B3SINGLE           | B3MULTI | [PropagationStyle.DATADOG] | [PropagationStyle.DATADOG] | [B3SINGLE]                       | [B3MULTI]
    null                     | null                     | null     | B3SINGLE           | B3MULTI | [PropagationStyle.DATADOG] | [PropagationStyle.DATADOG] | [B3SINGLE]                       | [B3MULTI]
    null                     | null                     | null     | null               | null    | [PropagationStyle.DATADOG] | [PropagationStyle.DATADOG] | [DATADOG, TRACECONTEXT, BAGGAGE] | [DATADOG, TRACECONTEXT, BAGGAGE]
    null                     | null                     | null     | null               | null    | [PropagationStyle.DATADOG] | [PropagationStyle.DATADOG] | [DATADOG, TRACECONTEXT, BAGGAGE] | [DATADOG, TRACECONTEXT, BAGGAGE]
    null                     | null                     | null     | "b3 single header" | null    | [PropagationStyle.DATADOG] | [PropagationStyle.DATADOG] | [B3SINGLE]                       | [DATADOG, TRACECONTEXT, BAGGAGE]
    null                     | null                     | null     | "b3"               | null    | [PropagationStyle.DATADOG] | [PropagationStyle.DATADOG] | [B3MULTI]                        | [DATADOG, TRACECONTEXT, BAGGAGE]
    // spotless:on
  }

  def "agent args are used by config"() {
    setup:
    AgentArgsInjector.injectAgentArgsConfig([(PREFIX + SERVICE_NAME): "args service name"])

    when:
    rebuildConfig()
    def config = new Config()

    then:
    config.serviceName == "args service name"
  }

  def "agent args override captured env props"() {
    setup:
    AgentArgsInjector.injectAgentArgsConfig([(PREFIX + SERVICE_NAME): "args service name"])

    def capturedEnv = [(SERVICE_NAME): "captured props service name"]
    FixedCapturedEnvironment.useFixedEnv(capturedEnv)

    when:
    def config = new Config()

    then:
    config.serviceName == "args service name"
  }

  def "sys props override agent args"() {
    setup:
    System.setProperty(PREFIX + SERVICE_NAME, "system prop service name")

    AgentArgsInjector.injectAgentArgsConfig([(PREFIX + SERVICE_NAME): "args service name"])

    when:
    def config = new Config()

    then:
    config.serviceName == "system prop service name"
  }

  def "env vars override agent args"() {
    setup:
    environmentVariables.set(DD_SERVICE_NAME_ENV, "env service name")

    AgentArgsInjector.injectAgentArgsConfig([(PREFIX + SERVICE_NAME): "args service name"])

    when:
    def config = new Config()

    then:
    config.serviceName == "env service name"
  }

  def "agent args are used for looking up properties file"() {
    setup:
    AgentArgsInjector.injectAgentArgsConfig([(PREFIX + CONFIGURATION_FILE): "src/test/resources/dd-java-tracer.properties"])

    when:
    def config = new Config()

    then:
    config.configFileStatus == "src/test/resources/dd-java-tracer.properties"
    config.serviceName == "set-in-properties"
  }

  def "fallback to properties file has lower priority than agent args"() {
    setup:
    AgentArgsInjector.injectAgentArgsConfig([
      (PREFIX + CONFIGURATION_FILE): "src/test/resources/dd-java-tracer.properties",
      (PREFIX + SERVICE_NAME)      : "args service name"
    ])

    when:
    def config = new Config()

    then:
    config.configFileStatus == "src/test/resources/dd-java-tracer.properties"
    config.serviceName == "args service name"
  }

  def "long running trace invalid initial flush_interval set to default: #configuredFlushInterval"() {
    when:
    def prop = new Properties()
    prop.setProperty(TRACE_LONG_RUNNING_ENABLED, "true")
    prop.setProperty(TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL, configuredFlushInterval)
    Config config = Config.get(prop)

    then:
    config.longRunningTraceEnabled == true
    config.longRunningTraceInitialFlushInterval == flushInterval

    where:
    configuredFlushInterval | flushInterval
    "invalid"     | DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL
    "-1"          | DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL
    "9"           | DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL
    "451"         | DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL
    "10"          | 10
    "450"         | 450
  }

  def "ssi injection enabled"() {
    when:
    def prop = new Properties()
    prop.setProperty(SSI_INJECTION_ENABLED, "tracer")
    Config config = Config.get(prop)

    then:
    config.ssiInjectionEnabled == "tracer"
  }

  def "ssi inject force"() {
    when:
    def prop = new Properties()
    prop.setProperty(SSI_INJECTION_FORCE, "true")
    Config config = Config.get(prop)

    then:
    config.ssiInjectionForce == true
  }

  def "instrumentation source"() {
    when:
    def prop = new Properties()
    prop.setProperty(INSTRUMENTATION_SOURCE, "ssi")
    Config config = Config.get(prop)

    then:
    config.instrumentationSource == "ssi"
  }

  def "long running trace invalid flush_interval set to default: #configuredFlushInterval"() {
    when:
    def prop = new Properties()
    prop.setProperty(TRACE_LONG_RUNNING_ENABLED, "true")
    prop.setProperty(TRACE_LONG_RUNNING_FLUSH_INTERVAL, configuredFlushInterval)
    Config config = Config.get(prop)

    then:
    config.longRunningTraceEnabled == true
    config.longRunningTraceFlushInterval == flushInterval

    where:
    configuredFlushInterval | flushInterval
    "invalid"     | DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL
    "-1"          | DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL
    "19"          | DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL
    "451"         | DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL
    "20"          | 20
    "450"         | 450
  }

  def "partial flush and min spans interaction"() {
    when:
    def prop = new Properties()
    if (configuredPartialEnabled != null) {
      prop.setProperty(PARTIAL_FLUSH_ENABLED, configuredPartialEnabled.toString())
    }
    if (configuredPartialMinSpans != null) {
      prop.setProperty(PARTIAL_FLUSH_MIN_SPANS, configuredPartialMinSpans.toString())
    }
    Config config = Config.get(prop)

    then:
    config.partialFlushMinSpans == partialMinSpans

    where:
    configuredPartialEnabled | configuredPartialMinSpans | partialMinSpans
    null                     | null                      | DEFAULT_PARTIAL_FLUSH_MIN_SPANS
    true                     | null                      | DEFAULT_PARTIAL_FLUSH_MIN_SPANS
    false                    | null                      | 0
    null                     | 47                        | 47
    true                     | 11                        | 11
    false                    | 17                        | 0
  }

  def "check profiling SSI auto-enablement"() {
    when:
    def prop = new Properties()
    prop.setProperty(PROFILING_ENABLED, enablementMode)
    prop.setProperty(PROFILING_START_DELAY, "1")
    prop.setProperty(PROFILING_START_FORCE_FIRST, "true")

    Config config = Config.get(prop)

    then:
    config.profilingEnabled == expectedEnabled
    config.profilingStartDelay == expectedStartDelay
    config.profilingStartForceFirst == expectedStartForceFirst

    where:
    // spotless:off
    enablementMode | expectedEnabled | expectedStartDelay             | expectedStartForceFirst
    "true"         | true            | 1                              | true
    "false"        | false           | 1                              | true
    "auto"         | true            | PROFILING_START_DELAY_DEFAULT  | PROFILING_START_FORCE_FIRST_DEFAULT
    // spotless:on
  }

  def "url for debugger with unix domain socket"() {
    when:
    def prop = new Properties()
    prop.setProperty(AGENT_HOST, "myhost")
    prop.setProperty(TRACE_AGENT_PORT, "1234")
    prop.setProperty(TRACE_AGENT_URL, "unix:///path/to/socket")

    Config config = Config.get(prop)

    then:
    config.finalDebuggerSymDBUrl == "http://localhost:8126/symdb/v1/input"
  }

  def "verify try/catch behavior for invalid strings for TRACE_PROPAGATION_BEHAVIOR_EXTRACT"() {
    setup:
    def prop = new Properties()
    prop.setProperty(TRACE_PROPAGATION_BEHAVIOR_EXTRACT, "test")

    when:
    Config config = Config.get(prop)

    then:
    config.tracePropagationBehaviorExtract == TracePropagationBehaviorExtract.CONTINUE
  }

  def "Intake client uses correct URL for site #site"() {
    setup:
    def config = Spy(Config.get())

    when:
    config.getSite() >> site

    then:
    config.getDefaultTelemetryUrl()

    where:
    site                | expectedUrl
    "datadoghq.com"     | "https://instrumentation-telemetry-intake.datadoghq.com/api/v2/apmtelemetry"
    "us3.datadoghq.com" | "https://instrumentation-telemetry-intake.us3.datadoghq.com/api/v2/apmtelemetry"
    "us5.datadoghq.com" | "https://instrumentation-telemetry-intake.us5.datadoghq.com/api/v2/apmtelemetry"
    "ap1.datadoghq.com" | "https://instrumentation-telemetry-intake.ap1.datadoghq.com/api/v2/apmtelemetry"
    "datadoghq.eu"      | "https://instrumentation-telemetry-intake.datadoghq.eu/api/v2/apmtelemetry"
    "datad0g.com"       | "https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry"
  }

  // Subclass for setting Strictness of ConfigHelper when using fake configs
  static class ConfigTestWithFakes extends ConfigTest {

    def strictness

    def setup(){
      strictness = ConfigHelper.get().configInversionStrictFlag()
      ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST)
    }

    def cleanup(){
      ConfigHelper.get().setConfigInversionStrict(strictness)
    }

    def "verify rule config #name"() {
      setup:
      environmentVariables.set("DD_TRACE_TEST_ENABLED", "true")
      environmentVariables.set("DD_TRACE_TEST_ENV_ENABLED", "true")
      environmentVariables.set("DD_TRACE_DISABLED_ENV_ENABLED", "false")

      System.setProperty("dd.trace.test.enabled", "false")
      System.setProperty("dd.trace.test-prop.enabled", "true")
      System.setProperty("dd.trace.disabled-prop.enabled", "false")

      expect:
      Config.get().isRuleEnabled(name) == enabled

      where:
      // spotless:off
      name            | enabled
      ""              | true
      "invalid"       | true
      "test-prop"     | true
      "Test-Prop"     | true
      "test-env"      | true
      "Test-Env"      | true
      "test"          | false
      "TEST"          | false
      "disabled-prop" | false
      "Disabled-Prop" | false
      "disabled-env"  | false
      "Disabled-Env"  | false
      // spotless:on
    }

    def "verify integration jmxfetch config"() {
      setup:
      environmentVariables.set("DD_JMXFETCH_ORDER_ENABLED", "false")
      environmentVariables.set("DD_JMXFETCH_TEST_ENV_ENABLED", "true")
      environmentVariables.set("DD_JMXFETCH_DISABLED_ENV_ENABLED", "false")

      System.setProperty("dd.jmxfetch.order.enabled", "true")
      System.setProperty("dd.jmxfetch.test-prop.enabled", "true")
      System.setProperty("dd.jmxfetch.disabled-prop.enabled", "false")

      expect:
      Config.get().isJmxFetchIntegrationEnabled(integrationNames, defaultEnabled) == expected

      where:
      // spotless:off
      names                          | defaultEnabled | expected
      []                             | true           | true
      []                             | false          | false
      ["invalid"]                    | true           | true
      ["invalid"]                    | false          | false
      ["test-prop"]                  | false          | true
      ["test-env"]                   | false          | true
      ["disabled-prop"]              | true           | false
      ["disabled-env"]               | true           | false
      ["other", "test-prop"]         | false          | true
      ["other", "test-env"]          | false          | true
      ["order"]                      | false          | true
      ["test-prop", "disabled-prop"] | false          | true
      ["disabled-env", "test-env"]   | false          | true
      ["test-prop", "disabled-prop"] | true           | false
      ["disabled-env", "test-env"]   | true           | false
      // spotless:on

      integrationNames = new TreeSet<>(names)
    }

    def "verify integration trace analytics config"() {
      setup:
      environmentVariables.set("DD_ORDER_ANALYTICS_ENABLED", "false")
      environmentVariables.set("DD_TEST_ENV_ANALYTICS_ENABLED", "true")
      environmentVariables.set("DD_DISABLED_ENV_ANALYTICS_ENABLED", "false")
      // trace prefix form should take precedence over the old non-prefix form
      environmentVariables.set("DD_ALIAS_ENV_ANALYTICS_ENABLED", "false")
      environmentVariables.set("DD_TRACE_ALIAS_ENV_ANALYTICS_ENABLED", "true")

      System.setProperty("dd.order.analytics.enabled", "true")
      System.setProperty("dd.test-prop.analytics.enabled", "true")
      System.setProperty("dd.disabled-prop.analytics.enabled", "false")
      // trace prefix form should take precedence over the old non-prefix form
      System.setProperty("dd.alias-prop.analytics.enabled", "false")
      System.setProperty("dd.trace.alias-prop.analytics.enabled", "true")

      expect:
      Config.get().isTraceAnalyticsIntegrationEnabled(integrationNames, defaultEnabled) == expected

      where:
      // spotless:off
      names                           | defaultEnabled | expected
      []                              | true           | true
      []                              | false          | false
      ["invalid"]                     | true           | true
      ["invalid"]                     | false          | false
      ["test-prop"]                   | false          | true
      ["test-env"]                    | false          | true
      ["disabled-prop"]               | true           | false
      ["disabled-env"]                | true           | false
      ["other", "test-prop"]          | false          | true
      ["other", "test-env"]           | false          | true
      ["order"]                       | false          | true
      ["test-prop", "disabled-prop"]  | false          | true
      ["disabled-env", "test-env"]    | false          | true
      ["test-prop", "disabled-prop"]  | true           | false
      ["disabled-env", "test-env"]    | true           | false
      ["alias-prop", "disabled-prop"] | false          | true
      ["disabled-env", "alias-env"]   | false          | true
      ["alias-prop", "disabled-prop"] | true           | false
      ["disabled-env", "alias-env"]   | true           | false
      // spotless:on

      integrationNames = new TreeSet<>(names)
    }

    def "test getFloatSettingFromEnvironment(#name)"() {
      setup:
      environmentVariables.set("DD_ENV_ZERO_TEST", "0.0")
      environmentVariables.set("DD_ENV_FLOAT_TEST", "1.0")
      environmentVariables.set("DD_FLOAT_TEST", "0.2")

      System.setProperty("dd.prop.zero.test", "0")
      System.setProperty("dd.prop.float.test", "0.3")
      System.setProperty("dd.float.test", "0.4")
      System.setProperty("dd.garbage.test", "garbage")
      System.setProperty("dd.negative.test", "-1")

      expect:
      Config.get().configProvider.getFloat(name, defaultValue) == (float) expected

      where:
      name              | expected
      // spotless:off
      "env.zero.test"   | 0.0
      "prop.zero.test"  | 0
      "env.float.test"  | 1.0
      "prop.float.test" | 0.3
      "float.test"      | 0.4
      "negative.test"   | -1.0
      "garbage.test"    | 10.0
      "default.test"    | 10.0
      // spotless:on

      defaultValue = 10.0
    }

    def "test getDoubleSettingFromEnvironment(#name)"() {
      setup:
      environmentVariables.set("DD_ENV_ZERO_TEST", "0.0")
      environmentVariables.set("DD_ENV_FLOAT_TEST", "1.0")
      environmentVariables.set("DD_FLOAT_TEST", "0.2")

      System.setProperty("dd.prop.zero.test", "0")
      System.setProperty("dd.prop.float.test", "0.3")
      System.setProperty("dd.float.test", "0.4")
      System.setProperty("dd.garbage.test", "garbage")
      System.setProperty("dd.negative.test", "-1")

      expect:
      Config.get().configProvider.getDouble(name, defaultValue) == (double) expected

      where:
      // spotless:off
      name              | expected
      "env.zero.test"   | 0.0
      "prop.zero.test"  | 0
      "env.float.test"  | 1.0
      "prop.float.test" | 0.3
      "float.test"      | 0.4
      "negative.test"   | -1.0
      "garbage.test"    | 10.0
      "default.test"    | 10.0
      // spotless:on

      defaultValue = 10.0
    }

    def "get analytics sample rate"() {
      setup:
      environmentVariables.set("DD_FOO_ANALYTICS_SAMPLE_RATE", "0.5")
      environmentVariables.set("DD_BAR_ANALYTICS_SAMPLE_RATE", "0.9")
      // trace prefix form should take precedence over the old non-prefix form
      environmentVariables.set("DD_ALIAS_ENV_ANALYTICS_SAMPLE_RATE", "0.8")
      environmentVariables.set("DD_TRACE_ALIAS_ENV_ANALYTICS_SAMPLE_RATE", "0.4")

      System.setProperty("dd.baz.analytics.sample-rate", "0.7")
      System.setProperty("dd.buzz.analytics.sample-rate", "0.3")
      // trace prefix form should take precedence over the old non-prefix form
      System.setProperty("dd.alias-prop.analytics.sample-rate", "0.1")
      System.setProperty("dd.trace.alias-prop.analytics.sample-rate", "0.2")

      when:
      String[] array = services.toArray(new String[0])
      def value = Config.get().getInstrumentationAnalyticsSampleRate(array)

      then:
      value == expected

      where:
      // spotless:off
      services                | expected
      ["foo"]                 | 0.5f
      ["baz"]                 | 0.7f
      ["doesnotexist"]        | 1.0f
      ["doesnotexist", "foo"] | 0.5f
      ["doesnotexist", "baz"] | 0.7f
      ["foo", "bar"]          | 0.5f
      ["bar", "foo"]          | 0.9f
      ["baz", "buzz"]         | 0.7f
      ["buzz", "baz"]         | 0.3f
      ["foo", "baz"]          | 0.5f
      ["baz", "foo"]          | 0.7f
      ["alias-env", "baz"]    | 0.4f
      ["alias-prop", "foo"]   | 0.2f
      // spotless:on
    }

    // Static methods test:
    def "configProvider.get* unit test"() {
      setup:
      def p = new Properties()
      p.setProperty("i", "13")
      p.setProperty("f", "42.42")
      def configProvider = ConfigProvider.withPropertiesOverride(p)

      expect:
      configProvider.getDouble("i", 40) == 13
      configProvider.getDouble("f", 41) == 42.42
      configProvider.getFloat("i", 40) == 13
      configProvider.getFloat("f", 41) == 42.42f
      configProvider.getInteger("b", 61) == 61
      configProvider.getInteger("i", 61) == 13
      configProvider.getBoolean("a", true) == true
    }
  }
}
