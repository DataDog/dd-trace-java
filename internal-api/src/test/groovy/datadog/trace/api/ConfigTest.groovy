package datadog.trace.api

import datadog.trace.api.env.FixedCapturedEnvironment
import datadog.trace.bootstrap.config.provider.ConfigConverter
import datadog.trace.bootstrap.config.provider.ConfigProvider
import datadog.trace.test.util.DDSpecification
import org.junit.Rule

import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_ERROR_STATUSES
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ERROR_STATUSES
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME
import static datadog.trace.api.DDTags.HOST_TAG
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG
import static datadog.trace.api.DDTags.RUNTIME_VERSION_TAG
import static datadog.trace.api.DDTags.SERVICE
import static datadog.trace.api.DDTags.SERVICE_TAG
import static datadog.trace.api.IdGenerationStrategy.RANDOM
import static datadog.trace.api.IdGenerationStrategy.SEQUENTIAL
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_CLASSFILE_DUMP_ENABLED
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_DIAGNOSTICS_INTERVAL
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_ENABLED
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_EXCLUDE_FILE
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_INSTRUMENT_THE_WORLD
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_METRICS_ENABLED
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_POLL_INTERVAL
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_PROBE_FILE_LOCATION
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_SNAPSHOT_URL
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_UPLOAD_BATCH_SIZE
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_UPLOAD_FLUSH_INTERVAL
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_UPLOAD_TIMEOUT
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_VERIFY_BYTECODE
import static datadog.trace.api.config.GeneralConfig.API_KEY
import static datadog.trace.api.config.GeneralConfig.API_KEY_FILE
import static datadog.trace.api.config.GeneralConfig.CONFIGURATION_FILE
import static datadog.trace.api.config.GeneralConfig.ENV
import static datadog.trace.api.config.GeneralConfig.GLOBAL_TAGS
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_HOST
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_PORT
import static datadog.trace.api.config.GeneralConfig.PERF_METRICS_ENABLED
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME
import static datadog.trace.api.config.GeneralConfig.SITE
import static datadog.trace.api.config.GeneralConfig.TAGS
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_IGNORED_RESOURCES
import static datadog.trace.api.config.GeneralConfig.VERSION
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CHECK_PERIOD
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_ENABLED
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_METRICS_CONFIGS
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_REFRESH_BEANS_PERIOD
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_HOST
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_PORT
import static datadog.trace.api.config.JmxFetchConfig.JMX_TAGS
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
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TAGS
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_COMPRESSION
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_PERIOD
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_TIMEOUT
import static datadog.trace.api.config.ProfilingConfig.PROFILING_URL
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_ENABLED
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_INITIAL_POLL_INTERVAL
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_MAX_PAYLOAD_SIZE
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_URL
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN
import static datadog.trace.api.config.TraceInstrumentationConfig.RUNTIME_CONTEXT_FIELD_INJECTION
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED
import static datadog.trace.api.config.TracerConfig.AGENT_HOST
import static datadog.trace.api.config.TracerConfig.AGENT_PORT_LEGACY
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.HTTP_CLIENT_ERROR_STATUSES
import static datadog.trace.api.config.TracerConfig.HTTP_SERVER_ERROR_STATUSES
import static datadog.trace.api.config.TracerConfig.ID_GENERATION_STRATEGY
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
import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT
import static datadog.trace.api.config.TracerConfig.TRACE_REPORT_HOSTNAME
import static datadog.trace.api.config.TracerConfig.TRACE_RESOLVER_ENABLED
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_OPERATION_RULES
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE

class ConfigTest extends DDSpecification {

  static final String PREFIX = "dd."

  @Rule
  public final FixedCapturedEnvironment fixedCapturedEnvironment = new FixedCapturedEnvironment()

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
  private static final DD_JMXFETCH_METRICS_CONFIGS_ENV = "DD_JMXFETCH_METRICS_CONFIGS"
  private static final DD_TRACE_AGENT_PORT_ENV = "DD_TRACE_AGENT_PORT"
  private static final DD_AGENT_PORT_LEGACY_ENV = "DD_AGENT_PORT"
  private static final DD_TRACE_REPORT_HOSTNAME = "DD_TRACE_REPORT_HOSTNAME"
  private static final DD_RUNTIME_METRICS_ENABLED_ENV = "DD_RUNTIME_METRICS_ENABLED"

  private static final DD_PROFILING_API_KEY_OLD_ENV = "DD_PROFILING_API_KEY"
  private static final DD_PROFILING_API_KEY_VERY_OLD_ENV = "DD_PROFILING_APIKEY"
  private static final DD_PROFILING_TAGS_ENV = "DD_PROFILING_TAGS"
  private static final DD_PROFILING_PROXY_PASSWORD_ENV = "DD_PROFILING_PROXY_PASSWORD"

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
    prop.setProperty(HTTP_SERVER_ERROR_STATUSES, "123-456,457,124-125,122")
    prop.setProperty(HTTP_CLIENT_ERROR_STATUSES, "111")
    prop.setProperty(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true")
    prop.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
    prop.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, "true")
    prop.setProperty(SPLIT_BY_TAGS, "some.tag1,some.tag2,some.tag1")
    prop.setProperty(PARTIAL_FLUSH_MIN_SPANS, "15")
    prop.setProperty(TRACE_REPORT_HOSTNAME, "true")
    prop.setProperty(RUNTIME_CONTEXT_FIELD_INJECTION, "false")
    prop.setProperty(PROPAGATION_STYLE_EXTRACT, "Datadog, B3")
    prop.setProperty(PROPAGATION_STYLE_INJECT, "B3, Datadog")
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

    prop.setProperty(REMOTE_CONFIG_ENABLED, "true")
    prop.setProperty(REMOTE_CONFIG_URL, "remote config url")
    prop.setProperty(REMOTE_CONFIG_INITIAL_POLL_INTERVAL, "3")
    prop.setProperty(REMOTE_CONFIG_MAX_PAYLOAD_SIZE, "2")

    prop.setProperty(DEBUGGER_ENABLED, "true")
    prop.setProperty(DEBUGGER_SNAPSHOT_URL, "snapshot url")
    prop.setProperty(DEBUGGER_PROBE_FILE_LOCATION, "file location")
    prop.setProperty(DEBUGGER_UPLOAD_TIMEOUT, "10")
    prop.setProperty(DEBUGGER_UPLOAD_FLUSH_INTERVAL, "1000")
    prop.setProperty(DEBUGGER_UPLOAD_BATCH_SIZE, "200")
    prop.setProperty(DEBUGGER_METRICS_ENABLED, "false")
    prop.setProperty(DEBUGGER_CLASSFILE_DUMP_ENABLED, "true")
    prop.setProperty(DEBUGGER_POLL_INTERVAL, "10")
    prop.setProperty(DEBUGGER_DIAGNOSTICS_INTERVAL, "60")
    prop.setProperty(DEBUGGER_VERIFY_BYTECODE, "true")
    prop.setProperty(DEBUGGER_INSTRUMENT_THE_WORLD, "true")
    prop.setProperty(DEBUGGER_EXCLUDE_FILE, "exclude file")

    when:
    Config config = Config.get(prop)

    then:
    config.configFileStatus == "no config file present"
    config.apiKey == "new api key" // we can still override via internal properties object
    config.site == "new site"
    config.serviceName == "something else"
    config.idGenerationStrategy == SEQUENTIAL
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
    config.httpServerErrorStatuses == toBitSet((122..457))
    config.httpClientErrorStatuses == toBitSet((111..111))
    config.httpClientSplitByDomain == true
    config.dbClientSplitByInstance == true
    config.dbClientSplitByInstanceTypeSuffix == true
    config.splitByTags == ["some.tag1", "some.tag2"].toSet()
    config.partialFlushMinSpans == 15
    config.reportHostName == true
    config.runtimeContextFieldInjection == false
    config.propagationStylesToExtract.toList() == [PropagationStyle.DATADOG, PropagationStyle.B3]
    config.propagationStylesToInject.toList() == [PropagationStyle.B3, PropagationStyle.DATADOG]
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

    config.profilingEnabled == true
    config.profilingUrl == "new url"
    config.mergedProfilingTags == [b: "2", f: "6", (HOST_TAG): "test-host", (RUNTIME_ID_TAG): config.getRuntimeId(),  (RUNTIME_VERSION_TAG): config.getRuntimeVersion(), (SERVICE_TAG): config.serviceName, (LANGUAGE_TAG_KEY): LANGUAGE_TAG_VALUE]
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
    config.remoteConfigInitialPollInterval == 3
    config.remoteConfigMaxPayloadSizeBytes == 2048

    config.debuggerEnabled == true
    config.getFinalDebuggerSnapshotUrl() == "snapshot url"
    config.debuggerProbeFileLocation == "file location"
    config.debuggerUploadTimeout == 10
    config.debuggerUploadFlushInterval == 1000
    config.debuggerUploadBatchSize == 200
    config.debuggerMetricsEnabled == false
    config.debuggerClassFileDumpEnabled == true
    config.debuggerPollInterval == 10
    config.debuggerDiagnosticsInterval == 60
    config.debuggerVerifyByteCode == true
    config.debuggerInstrumentTheWorld == true
    config.debuggerExcludeFile == "exclude file"
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
    System.setProperty(PREFIX + HTTP_SERVER_ERROR_STATUSES, "123-456,457,124-125,122")
    System.setProperty(PREFIX + HTTP_CLIENT_ERROR_STATUSES, "111")
    System.setProperty(PREFIX + HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true")
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, "true")
    System.setProperty(PREFIX + SPLIT_BY_TAGS, "some.tag3, some.tag2, some.tag1")
    System.setProperty(PREFIX + PARTIAL_FLUSH_MIN_SPANS, "25")
    System.setProperty(PREFIX + TRACE_REPORT_HOSTNAME, "true")
    System.setProperty(PREFIX + RUNTIME_CONTEXT_FIELD_INJECTION, "false")
    System.setProperty(PREFIX + PROPAGATION_STYLE_EXTRACT, "Datadog, B3")
    System.setProperty(PREFIX + PROPAGATION_STYLE_INJECT, "B3, Datadog")
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

    System.setProperty(PREFIX + REMOTE_CONFIG_ENABLED, "true")
    System.setProperty(PREFIX + REMOTE_CONFIG_URL, "remote config url")
    System.setProperty(PREFIX + REMOTE_CONFIG_INITIAL_POLL_INTERVAL, "3")
    System.setProperty(PREFIX + REMOTE_CONFIG_MAX_PAYLOAD_SIZE, "2")

    System.setProperty(PREFIX + DEBUGGER_ENABLED, "true")
    System.setProperty(PREFIX + DEBUGGER_SNAPSHOT_URL, "snapshot url")
    System.setProperty(PREFIX + DEBUGGER_PROBE_FILE_LOCATION, "file location")
    System.setProperty(PREFIX + DEBUGGER_UPLOAD_TIMEOUT, "10")
    System.setProperty(PREFIX + DEBUGGER_UPLOAD_FLUSH_INTERVAL, "1000")
    System.setProperty(PREFIX + DEBUGGER_UPLOAD_BATCH_SIZE, "200")
    System.setProperty(PREFIX + REMOTE_CONFIG_MAX_PAYLOAD_SIZE, "2")
    System.setProperty(PREFIX + DEBUGGER_METRICS_ENABLED, "false")
    System.setProperty(PREFIX + DEBUGGER_CLASSFILE_DUMP_ENABLED, "true")
    System.setProperty(PREFIX + DEBUGGER_POLL_INTERVAL, "10")
    System.setProperty(PREFIX + DEBUGGER_DIAGNOSTICS_INTERVAL, "60")
    System.setProperty(PREFIX + DEBUGGER_VERIFY_BYTECODE, "true")
    System.setProperty(PREFIX + DEBUGGER_INSTRUMENT_THE_WORLD, "true")
    System.setProperty(PREFIX + DEBUGGER_EXCLUDE_FILE, "exclude file")

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
    config.mergedSpanTags == [b: "2", c: "3"]
    config.mergedJmxTags == [b: "2", d: "4", (RUNTIME_ID_TAG): config.getRuntimeId(), (SERVICE_TAG): config.serviceName]
    config.requestHeaderTags == [e: "five"]
    config.httpServerErrorStatuses == toBitSet((122..457))
    config.httpClientErrorStatuses == toBitSet((111..111))
    config.httpClientSplitByDomain == true
    config.dbClientSplitByInstance == true
    config.dbClientSplitByInstanceTypeSuffix == true
    config.splitByTags == ["some.tag3", "some.tag2", "some.tag1"].toSet()
    config.partialFlushMinSpans == 25
    config.reportHostName == true
    config.runtimeContextFieldInjection == false
    config.propagationStylesToExtract.toList() == [PropagationStyle.DATADOG, PropagationStyle.B3]
    config.propagationStylesToInject.toList() == [PropagationStyle.B3, PropagationStyle.DATADOG]
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

    config.profilingEnabled == true
    config.profilingUrl == "new url"
    config.mergedProfilingTags == [b: "2", f: "6", (HOST_TAG): "test-host", (RUNTIME_ID_TAG): config.getRuntimeId(), (RUNTIME_VERSION_TAG): config.getRuntimeVersion(), (SERVICE_TAG): config.serviceName, (LANGUAGE_TAG_KEY): LANGUAGE_TAG_VALUE]
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
    config.remoteConfigInitialPollInterval == 3
    config.remoteConfigMaxPayloadSizeBytes == 2 * 1024

    config.debuggerEnabled == true
    config.debuggerProbeFileLocation == "file location"
    config.debuggerUploadTimeout == 10
    config.debuggerUploadFlushInterval == 1000
    config.debuggerUploadBatchSize == 200
    config.debuggerMetricsEnabled == false
    config.debuggerClassFileDumpEnabled == true
    config.debuggerPollInterval == 10
    config.debuggerDiagnosticsInterval == 60
    config.debuggerVerifyByteCode == true
    config.debuggerInstrumentTheWorld == true
    config.debuggerExcludeFile == "exclude file"
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
    environmentVariables.set(DD_JMXFETCH_METRICS_CONFIGS_ENV, "some/file")
    environmentVariables.set(DD_TRACE_REPORT_HOSTNAME, "true")

    when:
    def config = new Config()

    then:
    config.apiKey == "test-api-key"
    config.serviceName == "still something else"
    config.traceEnabled == false
    config.writerType == "LoggingWriter"
    config.propagationStylesToExtract.toList() == [PropagationStyle.B3, PropagationStyle.DATADOG]
    config.propagationStylesToInject.toList() == [PropagationStyle.DATADOG, PropagationStyle.B3]
    config.jmxFetchMetricsConfigs == ["some/file"]
    config.reportHostName == true
  }

  def "sys props override env vars"() {
    setup:
    environmentVariables.set(DD_SERVICE_NAME_ENV, "still something else")
    environmentVariables.set(DD_WRITER_TYPE_ENV, "LoggingWriter")
    environmentVariables.set(DD_PRIORITIZATION_TYPE_ENV, "EnsureTrace")
    environmentVariables.set(DD_TRACE_AGENT_PORT_ENV, "777")

    System.setProperty(PREFIX + SERVICE_NAME, "what we actually want")
    System.setProperty(PREFIX + WRITER_TYPE, "DDAgentWriter")
    System.setProperty(PREFIX + PRIORITIZATION_TYPE, "FastLane")
    System.setProperty(PREFIX + AGENT_HOST, "somewhere")
    System.setProperty(PREFIX + TRACE_AGENT_PORT, "123")

    when:
    def config = new Config()

    then:
    config.serviceName == "what we actually want"
    config.writerType == "DDAgentWriter"
    config.agentHost == "somewhere"
    config.agentPort == 123
    config.agentUrl == "http://somewhere:123"
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
    System.setProperty(PREFIX + SPAN_TAGS, "invalid")
    System.setProperty(PREFIX + HTTP_SERVER_ERROR_STATUSES, "1111")
    System.setProperty(PREFIX + HTTP_CLIENT_ERROR_STATUSES, "1:1")
    System.setProperty(PREFIX + HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "invalid")
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "invalid")
    System.setProperty(PREFIX + PROPAGATION_STYLE_EXTRACT, "some garbage")
    System.setProperty(PREFIX + PROPAGATION_STYLE_INJECT, " ")

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
    config.httpServerErrorStatuses == toBitSet((500..599))
    config.httpClientErrorStatuses == toBitSet((400..499))
    config.httpClientSplitByDomain == false
    config.dbClientSplitByInstance == false
    config.dbClientSplitByInstanceTypeSuffix == false
    config.splitByTags == [].toSet()
    config.propagationStylesToExtract.toList() == [PropagationStyle.DATADOG]
    config.propagationStylesToInject.toList() == [PropagationStyle.DATADOG]
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
    properties.setProperty(HTTP_SERVER_ERROR_STATUSES, "123-456,457,124-125,122")
    properties.setProperty(HTTP_CLIENT_ERROR_STATUSES, "111")
    properties.setProperty(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true")
    properties.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
    properties.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, "true")
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
    config.httpServerErrorStatuses == toBitSet((122..457))
    config.httpClientErrorStatuses == toBitSet((111..111))
    config.httpClientSplitByDomain == true
    config.dbClientSplitByInstance == true
    config.dbClientSplitByInstanceTypeSuffix == true
    config.splitByTags == [].toSet()
    config.partialFlushMinSpans == 15
    config.propagationStylesToExtract.toList() == [PropagationStyle.B3, PropagationStyle.DATADOG]
    config.propagationStylesToInject.toList() == [PropagationStyle.DATADOG, PropagationStyle.B3]
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
    def capturedEnv = new HashMap<String, String>()
    capturedEnv.put(SERVICE_NAME, "automatic service name")
    fixedCapturedEnvironment.load(capturedEnv)

    when:
    def config = new Config()

    then:
    config.serviceName == "automatic service name"
  }

  def "specify props override captured env props"() {
    setup:
    def prop = new Properties()
    prop.setProperty(SERVICE_NAME, "what actually wants")

    def capturedEnv = new HashMap<String, String>()
    capturedEnv.put(SERVICE_NAME, "something else")
    fixedCapturedEnvironment.load(capturedEnv)

    when:
    def config = Config.get(prop)

    then:
    config.serviceName == "what actually wants"
  }

  def "sys props override captured env props"() {
    setup:
    System.setProperty(PREFIX + SERVICE_NAME, "what actually wants")

    def capturedEnv = new HashMap<String, String>()
    capturedEnv.put(SERVICE_NAME, "something else")
    fixedCapturedEnvironment.load(capturedEnv)

    when:
    def config = new Config()

    then:
    config.serviceName == "what actually wants"
  }

  def "env vars override captured env props"() {
    setup:
    environmentVariables.set(DD_SERVICE_NAME_ENV, "what actually wants")

    def capturedEnv = new HashMap<String, String>()
    capturedEnv.put(SERVICE_NAME, "something else")
    fixedCapturedEnvironment.load(capturedEnv)

    when:
    def config = new Config()

    then:
    config.serviceName == "what actually wants"
  }

  def "verify integration config"() {
    setup:
    environmentVariables.set("DD_INTEGRATION_ORDER_ENABLED", "false")
    environmentVariables.set("DD_INTEGRATION_TEST_ENV_ENABLED", "true")
    environmentVariables.set("DD_INTEGRATION_DISABLED_ENV_ENABLED", "false")

    System.setProperty("dd.integration.order.enabled", "true")
    System.setProperty("dd.integration.test-prop.enabled", "true")
    System.setProperty("dd.integration.disabled-prop.enabled", "false")

    expect:
    Config.get().isIntegrationEnabled(integrationNames, defaultEnabled) == expected

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
    "1"                 | null
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
    System.getenv(DD_ENV_ENV) == null
    System.getenv(DD_VERSION_ENV) == null
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
    System.getenv(DD_ENV_ENV) == null
    System.getenv(DD_VERSION_ENV) == null
    //actual guard:
    config.mergedSpanTags == [(VERSION): "42"]
  }

  def "set of version exclusively by DD_VERSION and without DD_ENV "() {
    setup:
    environmentVariables.set(DD_VERSION_ENV, "3.2.1")

    when:
    Config config = new Config()

    then:
    System.getenv(DD_ENV_ENV) == null
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
    config.mergedSpanTags == [service: 'service-tag-in-dd-trace-global-tags-java-property', 'service.version': 'my-svc-vers']
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
    config.mergedSpanTags == [service: 'service-tag-in-dd-trace-global-tags-java-property', 'service.version': 'my-svc-vers']
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
    config.mergedSpanTags == [service: 'service-tag-in-dd-trace-global-tags-java-property', 'service.version': 'my-svc-vers']
    config.mergedJmxTags == [(RUNTIME_ID_TAG) : config.getRuntimeId(), (SERVICE_TAG): config.serviceName,
      'service.version': 'my-svc-vers']
  }

  def "set servicenaem by DD_SERVICE"() {
    setup:
    environmentVariables.set("DD_SERVICE", "dd-service-env-var")
    System.setProperty(PREFIX + GLOBAL_TAGS, "service:service-tag-in-dd-trace-global-tags-java-property,service.version:my-svc-vers")
    environmentVariables.set(DD_GLOBAL_TAGS_ENV, "service:service-tag-in-env-var,service.version:my-svc-vers")

    when:
    def config = new Config()

    then:
    config.serviceName == "dd-service-env-var"
    config.mergedSpanTags == [service: 'service-tag-in-dd-trace-global-tags-java-property', 'service.version': 'my-svc-vers']
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

  def "valueOf positive test"() {
    expect:
    ConfigConverter.valueOf(value, tClass) == expected

    where:
    // spotless:off
    value       | tClass  | expected
    "42.42"     | Boolean | false
    "42.42"     | Boolean | false
    "true"      | Boolean | true
    "trUe"      | Boolean | true
    "trUe"      | Boolean | true
    "tru"       | Boolean | false
    "truee"     | Boolean | false
    "true "     | Boolean | false
    " true"     | Boolean | false
    " true "    | Boolean | false
    "   true  " | Boolean | false
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
    config.idGenerationStrategy == RANDOM
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

  def "trace_agent_url overrides either host and port or unix domain"() {
    setup:
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
    configuredUrl                     | expectedUrl             | expectedHost | expectedPort | expectedUnixDomainSocket
    null                              | "http://localhost:8126" | "localhost"  | 8126         | "/path/to/socket"
    ""                                | "http://localhost:8126" | "localhost"  | 8126         | "/path/to/socket"
    "http://localhost:1234"           | "http://localhost:1234" | "localhost"  | 1234         | "/path/to/socket"
    "http://somehost"                 | "http://somehost:8126"  | "somehost"   | 8126         | "/path/to/socket"
    "http://somehost:80"              | "http://somehost:80"    | "somehost"   | 80           | "/path/to/socket"
    "https://somehost:8143"           | "https://somehost:8143" | "somehost"   | 8143         | "/path/to/socket"
    "unix:///another/socket/path"     | "http://localhost:8126" | "localhost"  | 8126         | "/another/socket/path"
    "unix:///another%2Fsocket%2Fpath" | "http://localhost:8126" | "localhost"  | 8126         | "/another/socket/path"
    "http:"                           | "http://localhost:8126" | "localhost"  | 8126         | "/path/to/socket"
    "unix:"                           | "http://localhost:8126" | "localhost"  | 8126         | "/path/to/socket"
    "1234"                            | "http://localhost:8126" | "localhost"  | 8126         | "/path/to/socket"
    ":1234"                           | "http://localhost:8126" | "localhost"  | 8126         | "/path/to/socket"
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
}
