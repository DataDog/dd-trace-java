package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_BAGGAGE_MAX_BYTES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_BAGGAGE_MAX_ITEMS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL;
import static datadog.trace.api.DDTags.HOST_TAG;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;
import static datadog.trace.api.DDTags.RUNTIME_VERSION_TAG;
import static datadog.trace.api.DDTags.SERVICE;
import static datadog.trace.api.DDTags.SERVICE_TAG;
import static datadog.trace.api.TracePropagationBehaviorExtract.RESTART;
import static datadog.trace.api.TracePropagationStyle.B3MULTI;
import static datadog.trace.api.TracePropagationStyle.B3SINGLE;
import static datadog.trace.api.TracePropagationStyle.BAGGAGE;
import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT;
import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_DYNAMIC_ATR_BUCKETS;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_DYNAMIC_ATR_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_EXCLUDE_FILES;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_INSTRUMENT_THE_WORLD;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_METRICS_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_POLL_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_PROBE_FILE;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_SNAPSHOT_URL;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_INTERVAL_SECONDS;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE;
import static datadog.trace.api.config.DebuggerConfig.EXCEPTION_REPLAY_ENABLED;
import static datadog.trace.api.config.GeneralConfig.API_KEY;
import static datadog.trace.api.config.GeneralConfig.API_KEY_FILE;
import static datadog.trace.api.config.GeneralConfig.CONFIGURATION_FILE;
import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.GLOBAL_TAGS;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_HOST;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_PORT;
import static datadog.trace.api.config.GeneralConfig.INSTRUMENTATION_SOURCE;
import static datadog.trace.api.config.GeneralConfig.JDK_SOCKET_ENABLED;
import static datadog.trace.api.config.GeneralConfig.PERF_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.SITE;
import static datadog.trace.api.config.GeneralConfig.SSI_INJECTION_ENABLED;
import static datadog.trace.api.config.GeneralConfig.SSI_INJECTION_FORCE;
import static datadog.trace.api.config.GeneralConfig.TAGS;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_IGNORED_RESOURCES;
import static datadog.trace.api.config.GeneralConfig.TRACE_AWS_LAMBDA_STRIP_CONTEXT_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CHECK_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_ENABLED;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_METRICS_CONFIGS;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_REFRESH_BEANS_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_HOST;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_PORT;
import static datadog.trace.api.config.JmxFetchConfig.JMX_TAGS;
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_AGENTLESS_ENABLED;
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_ENABLED;
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_ML_APP;
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_ENABLED;
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_EXPORTER;
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_INTERVAL;
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_TIMEOUT;
import static datadog.trace.api.config.OtlpConfig.OTEL_TRACES_SPAN_METRICS_ENABLED;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_ENDPOINT;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_HEADERS;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_PROTOCOL;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_TEMPORALITY_PREFERENCE;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_TIMEOUT;
import static datadog.trace.api.config.OtlpConfig.OTLP_TRACES_ENDPOINT;
import static datadog.trace.api.config.OtlpConfig.OTLP_TRACES_HEADERS;
import static datadog.trace.api.config.OtlpConfig.OTLP_TRACES_PROTOCOL;
import static datadog.trace.api.config.OtlpConfig.OTLP_TRACES_TIMEOUT;
import static datadog.trace.api.config.OtlpConfig.Protocol.HTTP_JSON;
import static datadog.trace.api.config.OtlpConfig.Protocol.HTTP_PROTOBUF;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_ENABLED;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_EXPORTER;
import static datadog.trace.api.config.OtlpConfig.Temporality.CUMULATIVE;
import static datadog.trace.api.config.OtlpConfig.Temporality.DELTA;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AGENTLESS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_VERY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_HOST;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PASSWORD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PORT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_USERNAME;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_DELAY;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_DELAY_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TAGS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_COMPRESSION;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_PERIOD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_URL;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_POLL_INTERVAL_SECONDS;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_URL;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_HOST;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_PROPAGATION_ORACLE_ACTION_ONLY_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_METADATA_FETCHING_ON_CONNECT;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_METADATA_FETCHING_ON_QUERY;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN;
import static datadog.trace.api.config.TraceInstrumentationConfig.RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static datadog.trace.api.config.TracerConfig.AGENT_HOST;
import static datadog.trace.api.config.TracerConfig.AGENT_PORT_LEGACY;
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET;
import static datadog.trace.api.config.TracerConfig.BAGGAGE_MAPPING;
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.ID_GENERATION_STRATEGY;
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_ENABLED;
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.config.TracerConfig.PRIORITIZATION_TYPE;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING;
import static datadog.trace.api.config.TracerConfig.PROPAGATION_STYLE_EXTRACT;
import static datadog.trace.api.config.TracerConfig.PROPAGATION_STYLE_INJECT;
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.RESPONSE_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING;
import static datadog.trace.api.config.TracerConfig.SPAN_TAGS;
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PORT;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_URL;
import static datadog.trace.api.config.TracerConfig.TRACE_BAGGAGE_MAX_BYTES;
import static datadog.trace.api.config.TracerConfig.TRACE_BAGGAGE_MAX_ITEMS;
import static datadog.trace.api.config.TracerConfig.TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING;
import static datadog.trace.api.config.TracerConfig.TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING;
import static datadog.trace.api.config.TracerConfig.TRACE_EXPERIMENTAL_FEATURES_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_LONG_RUNNING_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_LONG_RUNNING_FLUSH_INTERVAL;
import static datadog.trace.api.config.TracerConfig.TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL;
import static datadog.trace.api.config.TracerConfig.TRACE_PEER_SERVICE_DEFAULTS_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_BEHAVIOR_EXTRACT;
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_EXTRACT_FIRST;
import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_REPORT_HOSTNAME;
import static datadog.trace.api.config.TracerConfig.TRACE_RESOLVER_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_OPERATION_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SERVICE_DISCOVERY_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA;
import static datadog.trace.api.config.TracerConfig.TRACE_X_DATADOG_TAGS_MAX_LENGTH;
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE;
import static datadog.trace.api.env.FixedCapturedEnvironment.useFixedEnv;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED;
import static datadog.trace.bootstrap.config.provider.ConfigProvider.withPropertiesOverride;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import datadog.environment.EnvironmentVariables;
import datadog.trace.bootstrap.config.provider.AgentArgsInjector;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import datadog.trace.test.junit.utils.config.WithConfigExtension.TestEnvironmentVariables;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class ConfigTest {
  private static final String PREFIX = "dd.";
  private static final String DD_API_KEY_ENV = "DD_API_KEY";
  private static final String DD_SERVICE_NAME_ENV = "DD_SERVICE_NAME";
  private static final String DD_TRACE_ENABLED_ENV = "DD_TRACE_ENABLED";
  private static final String DD_WRITER_TYPE_ENV = "DD_WRITER_TYPE";
  private static final String DD_PRIORITIZATION_TYPE_ENV = "DD_PRIORITIZATION_TYPE";
  private static final String DD_SERVICE_MAPPING_ENV = "DD_SERVICE_MAPPING";
  private static final String DD_TAGS_ENV = "DD_TAGS";
  private static final String DD_ENV_ENV = "DD_ENV";
  private static final String DD_VERSION_ENV = "DD_VERSION";
  private static final String DD_GLOBAL_TAGS_ENV = "DD_TRACE_GLOBAL_TAGS";
  private static final String DD_SPAN_TAGS_ENV = "DD_TRACE_SPAN_TAGS";
  private static final String DD_HEADER_TAGS_ENV = "DD_TRACE_HEADER_TAGS";
  private static final String DD_JMX_TAGS_ENV = "DD_TRACE_JMX_TAGS";
  private static final String DD_PROPAGATION_STYLE_EXTRACT = "DD_PROPAGATION_STYLE_EXTRACT";
  private static final String DD_PROPAGATION_STYLE_INJECT = "DD_PROPAGATION_STYLE_INJECT";
  private static final String DD_TRACE_PROPAGATION_EXTRACT_FIRST =
      "DD_TRACE_PROPAGATION_EXTRACT_FIRST";
  private static final String DD_JMXFETCH_METRICS_CONFIGS_ENV = "DD_JMXFETCH_METRICS_CONFIGS";
  private static final String DD_TRACE_AGENT_PORT_ENV = "DD_TRACE_AGENT_PORT";
  private static final String DD_AGENT_PORT_LEGACY_ENV = "DD_AGENT_PORT";
  private static final String DD_TRACE_HEADER_TAGS = "DD_TRACE_HEADER_TAGS";
  private static final String DD_TRACE_REPORT_HOSTNAME = "DD_TRACE_REPORT_HOSTNAME";
  private static final String DD_RUNTIME_METRICS_ENABLED_ENV = "DD_RUNTIME_METRICS_ENABLED";
  private static final String DD_TRACE_LONG_RUNNING_ENABLED =
      "DD_TRACE_EXPERIMENTAL_LONG_RUNNING_ENABLED";
  private static final String DD_TRACE_LONG_RUNNING_FLUSH_INTERVAL =
      "DD_TRACE_EXPERIMENTAL_LONG_RUNNING_FLUSH_INTERVAL";
  private static final String DD_PROFILING_API_KEY_OLD_ENV = "DD_PROFILING_API_KEY";
  private static final String DD_PROFILING_API_KEY_VERY_OLD_ENV = "DD_PROFILING_APIKEY";
  private static final String DD_PROFILING_TAGS_ENV = "DD_PROFILING_TAGS";
  private static final String DD_PROFILING_PROXY_PASSWORD_ENV = "DD_PROFILING_PROXY_PASSWORD";
  private static final String DD_TRACE_X_DATADOG_TAGS_MAX_LENGTH =
      "DD_TRACE_X_DATADOG_TAGS_MAX_LENGTH";
  private static final String DD_LLMOBS_ENABLED_ENV = "DD_LLMOBS_ENABLED";
  private static final String DD_LLMOBS_ML_APP_ENV = "DD_LLMOBS_ML_APP";
  private static final String DD_LLMOBS_AGENTLESS_ENABLED_ENV = "DD_LLMOBS_AGENTLESS_ENABLED";
  private static final String DD_TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING_ENV =
      "DD_TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING";
  private static final String DD_TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING_ENV =
      "DD_TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING";

  private static final String DD_SFN_INJECT_DATADOG_ATTRIBUTE_ENABLED_ENV =
      "DD_SFN_INJECT_DATADOG_ATTRIBUTE_ENABLED";
  private static final String DD_SNS_INJECT_DATADOG_ATTRIBUTE_ENABLED_ENV =
      "DD_SNS_INJECT_DATADOG_ATTRIBUTE_ENABLED";
  private static final String DD_SQS_INJECT_DATADOG_ATTRIBUTE_ENABLED_ENV =
      "DD_SQS_INJECT_DATADOG_ATTRIBUTE_ENABLED";
  private static final String DD_EVENTBRIDGE_INJECT_DATADOG_ATTRIBUTE_ENABLED_ENV =
      "DD_EVENTBRIDGE_INJECT_DATADOG_ATTRIBUTE_ENABLED";

  private static final String EVENTBRIDGE_INJECT_DATADOG_ATTRIBUTE_ENABLED =
      "eventbridge.inject.datadog.attribute.enabled";
  private static final String SFN_INJECT_DATADOG_ATTRIBUTE_ENABLED =
      "sfn.inject.datadog.attribute.enabled";
  private static final String SNS_INJECT_DATADOG_ATTRIBUTE_ENABLED =
      "sns.inject.datadog.attribute.enabled";
  private static final String SQS_INJECT_DATADOG_ATTRIBUTE_ENABLED =
      "sqs.inject.datadog.attribute.enabled";

  private static final String DD_TRACE_OTEL_ENABLED_ENV = "DD_TRACE_OTEL_ENABLED";
  private static final String DD_TRACE_OTEL_ENABLED_PROP = "dd.trace.otel.enabled";

  private static final String DD_METRICS_OTEL_ENABLED_ENV = "DD_METRICS_OTEL_ENABLED";
  private static final String DD_METRICS_OTEL_ENABLED_PROP = "dd.metrics.otel.enabled";

  private static final String OTEL_RESOURCE_ATTRIBUTES_ENV = "OTEL_RESOURCE_ATTRIBUTES";
  private static final String OTEL_RESOURCE_ATTRIBUTES_PROP = "otel.resource.attributes";

  private static final String OTEL_METRIC_EXPORT_TIMEOUT_ENV = "OTEL_METRIC_EXPORT_TIMEOUT";
  private static final String OTEL_METRIC_EXPORT_TIMEOUT_PROP = "otel.metric.export.timeout";
  private static final String OTEL_METRIC_EXPORT_INTERVAL_ENV = "OTEL_METRIC_EXPORT_INTERVAL";
  private static final String OTEL_METRIC_EXPORT_INTERVAL_PROP = "otel.metric.export.interval";

  private static final String OTEL_EXPORTER_OTLP_ENDPOINT_PROP = "otel.exporter.otlp.endpoint";
  private static final String OTEL_EXPORTER_OTLP_HEADERS_PROP = "otel.exporter.otlp.headers";
  private static final String OTEL_EXPORTER_OTLP_PROTOCOL_PROP = "otel.exporter.otlp.protocol";
  private static final String OTEL_EXPORTER_OTLP_TIMEOUT_PROP = "otel.exporter.otlp.timeout";

  private static final String OTEL_EXPORTER_OTLP_METRICS_ENDPOINT_ENV =
      "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT";
  private static final String OTEL_EXPORTER_OTLP_METRICS_ENDPOINT_PROP =
      "otel.exporter.otlp.metrics.endpoint";
  private static final String OTEL_EXPORTER_OTLP_METRICS_HEADERS_ENV =
      "OTEL_EXPORTER_OTLP_METRICS_HEADERS";
  private static final String OTEL_EXPORTER_OTLP_METRICS_HEADERS_PROP =
      "otel.exporter.otlp.metrics.headers";
  private static final String OTEL_EXPORTER_OTLP_METRICS_PROTOCOL_ENV =
      "OTEL_EXPORTER_OTLP_METRICS_PROTOCOL";
  private static final String OTEL_EXPORTER_OTLP_METRICS_PROTOCOL_PROP =
      "otel.exporter.otlp.metrics.protocol";
  private static final String OTEL_EXPORTER_OTLP_METRICS_TIMEOUT_ENV =
      "OTEL_EXPORTER_OTLP_METRICS_TIMEOUT";
  private static final String OTEL_EXPORTER_OTLP_METRICS_TIMEOUT_PROP =
      "otel.exporter.otlp.metrics.timeout";

  private static final String OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE_ENV =
      "OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE";
  private static final String OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE_PROP =
      "otel.exporter.otlp.metrics.temporality.preference";

  private static final String OTEL_TRACES_EXPORTER_ENV = "OTEL_TRACES_EXPORTER";
  private static final String OTEL_TRACES_EXPORTER_PROP = "otel.traces.exporter";

  private static final String OTEL_EXPORTER_OTLP_TRACES_ENDPOINT_ENV =
      "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT";
  private static final String OTEL_EXPORTER_OTLP_TRACES_ENDPOINT_PROP =
      "otel.exporter.otlp.traces.endpoint";
  private static final String OTEL_EXPORTER_OTLP_TRACES_HEADERS_ENV =
      "OTEL_EXPORTER_OTLP_TRACES_HEADERS";
  private static final String OTEL_EXPORTER_OTLP_TRACES_HEADERS_PROP =
      "otel.exporter.otlp.traces.headers";
  private static final String OTEL_EXPORTER_OTLP_TRACES_PROTOCOL_ENV =
      "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL";
  private static final String OTEL_EXPORTER_OTLP_TRACES_PROTOCOL_PROP =
      "otel.exporter.otlp.traces.protocol";
  private static final String OTEL_EXPORTER_OTLP_TRACES_TIMEOUT_ENV =
      "OTEL_EXPORTER_OTLP_TRACES_TIMEOUT";
  private static final String OTEL_EXPORTER_OTLP_TRACES_TIMEOUT_PROP =
      "otel.exporter.otlp.traces.timeout";

  @BeforeEach
  void useEmptyCapturedEnvironment() {
    useFixedEnv(emptyMap());
  }

  @Test
  void specifyOverridesViaProperties() {
    Properties prop = new Properties();
    prop.setProperty(API_KEY, "new api key");
    prop.setProperty(SITE, "new site");
    prop.setProperty(SERVICE_NAME, "something else");
    prop.setProperty(TRACE_ENABLED, "false");
    prop.setProperty(ID_GENERATION_STRATEGY, "SEQUENTIAL");
    prop.setProperty(WRITER_TYPE, "LoggingWriter");
    prop.setProperty(AGENT_HOST, "somehost");
    prop.setProperty(TRACE_AGENT_PORT, "123");
    prop.setProperty(AGENT_UNIX_DOMAIN_SOCKET, "somepath");
    prop.setProperty(AGENT_PORT_LEGACY, "456");
    prop.setProperty(PRIORITY_SAMPLING, "false");
    prop.setProperty(TRACE_RESOLVER_ENABLED, "false");
    prop.setProperty(SERVICE_MAPPING, "a:1:2");
    prop.setProperty(GLOBAL_TAGS, "b:2");
    prop.setProperty(SPAN_TAGS, "c:3");
    prop.setProperty(JMX_TAGS, "d:4");
    prop.setProperty(HEADER_TAGS, "e:five");
    prop.setProperty(BAGGAGE_MAPPING, "f:six,g");
    prop.setProperty(HTTP_SERVER_ERROR_STATUSES, "123-456,457,124-125,122");
    prop.setProperty(HTTP_CLIENT_ERROR_STATUSES, "111");
    prop.setProperty(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true");
    prop.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true");
    prop.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, "true");
    prop.setProperty(DB_CLIENT_HOST_SPLIT_BY_HOST, "true");
    prop.setProperty(SPLIT_BY_TAGS, "some.tag1,some.tag2,some.tag1");
    prop.setProperty(PARTIAL_FLUSH_MIN_SPANS, "15");
    prop.setProperty(TRACE_REPORT_HOSTNAME, "true");
    prop.setProperty(RUNTIME_CONTEXT_FIELD_INJECTION, "false");
    prop.setProperty(PROPAGATION_STYLE_EXTRACT, "Datadog, B3");
    prop.setProperty(PROPAGATION_STYLE_INJECT, "B3, Datadog");
    prop.setProperty(TRACE_PROPAGATION_EXTRACT_FIRST, "false");
    prop.setProperty(TRACE_PROPAGATION_BEHAVIOR_EXTRACT, "restart");
    prop.setProperty(JMX_FETCH_ENABLED, "false");
    prop.setProperty(JMX_FETCH_METRICS_CONFIGS, "/foo.yaml,/bar.yaml");
    prop.setProperty(JMX_FETCH_CHECK_PERIOD, "100");
    prop.setProperty(JMX_FETCH_REFRESH_BEANS_PERIOD, "200");
    prop.setProperty(JMX_FETCH_STATSD_HOST, "statsd host");
    prop.setProperty(JMX_FETCH_STATSD_PORT, "321");
    prop.setProperty(HEALTH_METRICS_ENABLED, "true");
    prop.setProperty(HEALTH_METRICS_STATSD_HOST, "metrics statsd host");
    prop.setProperty(HEALTH_METRICS_STATSD_PORT, "654");
    prop.setProperty(TRACE_SAMPLING_SERVICE_RULES, "a:1");
    prop.setProperty(TRACE_SAMPLING_OPERATION_RULES, "b:1");
    prop.setProperty(TRACE_SAMPLE_RATE, ".5");
    prop.setProperty(TRACE_RATE_LIMIT, "200");
    prop.setProperty(TRACE_LONG_RUNNING_ENABLED, "true");
    prop.setProperty(TRACE_LONG_RUNNING_FLUSH_INTERVAL, "250");

    prop.setProperty(
        TRACE_EXPERIMENTAL_FEATURES_ENABLED, "DD_TAGS, DD_TRACE_HTTP_CLIENT_TAG_QUERY_STRING");

    prop.setProperty(PROFILING_ENABLED, "true");
    prop.setProperty(PROFILING_URL, "new url");
    prop.setProperty(PROFILING_TAGS, "f:6,host:test-host");
    prop.setProperty(PROFILING_START_DELAY, "1111");
    prop.setProperty(PROFILING_START_FORCE_FIRST, "true");
    prop.setProperty(PROFILING_UPLOAD_PERIOD, "1112");
    prop.setProperty(PROFILING_TEMPLATE_OVERRIDE_FILE, "/path");
    prop.setProperty(PROFILING_UPLOAD_TIMEOUT, "1116");
    prop.setProperty(PROFILING_UPLOAD_COMPRESSION, "off");
    prop.setProperty(PROFILING_PROXY_HOST, "proxy-host");
    prop.setProperty(PROFILING_PROXY_PORT, "1118");
    prop.setProperty(PROFILING_PROXY_USERNAME, "proxy-username");
    prop.setProperty(PROFILING_PROXY_PASSWORD, "proxy-password");
    prop.setProperty(PROFILING_EXCEPTION_SAMPLE_LIMIT, "811");
    prop.setProperty(PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS, "1121");
    prop.setProperty(PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE, "1122");
    prop.setProperty(PROFILING_AGENTLESS, "true");

    prop.setProperty(REMOTE_CONFIGURATION_ENABLED, "true");
    prop.setProperty(REMOTE_CONFIG_URL, "remote config url");
    prop.setProperty(REMOTE_CONFIG_POLL_INTERVAL_SECONDS, "3");
    prop.setProperty(REMOTE_CONFIG_MAX_PAYLOAD_SIZE, "2");

    prop.setProperty(DYNAMIC_INSTRUMENTATION_ENABLED, "true");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_PROBE_FILE, "file location");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT, "10");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_UPLOAD_INTERVAL_SECONDS, "0.234");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE, "200");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_METRICS_ENABLED, "false");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED, "true");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_POLL_INTERVAL, "10");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL, "60");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE, "true");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_INSTRUMENT_THE_WORLD, "method");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_EXCLUDE_FILES, "exclude file");
    prop.setProperty(DYNAMIC_INSTRUMENTATION_SNAPSHOT_URL, "http://somehost:123/debugger/v1/input");
    prop.setProperty(EXCEPTION_REPLAY_ENABLED, "true");
    prop.setProperty(TRACE_X_DATADOG_TAGS_MAX_LENGTH, "128");
    prop.setProperty(JDK_SOCKET_ENABLED, "false");

    prop.setProperty(TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, "all");
    prop.setProperty(TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, "all");

    prop.setProperty(SFN_INJECT_DATADOG_ATTRIBUTE_ENABLED, "false");
    prop.setProperty(EVENTBRIDGE_INJECT_DATADOG_ATTRIBUTE_ENABLED, "false");
    prop.setProperty(SNS_INJECT_DATADOG_ATTRIBUTE_ENABLED, "false");
    prop.setProperty(SQS_INJECT_DATADOG_ATTRIBUTE_ENABLED, "false");

    prop.setProperty(METRICS_OTEL_ENABLED, "True");
    prop.setProperty(METRICS_OTEL_INTERVAL, "11000");
    prop.setProperty(METRICS_OTEL_TIMEOUT, "9000");
    prop.setProperty(OTLP_METRICS_ENDPOINT, "http://localhost:4333/v1/metrics");
    prop.setProperty(
        OTLP_METRICS_HEADERS, "api-key=key,other-config-value=value,metrics-config-value=M");
    prop.setProperty(OTLP_METRICS_PROTOCOL, "http/protobuf");
    prop.setProperty(OTLP_METRICS_TIMEOUT, "5001");
    prop.setProperty(OTLP_METRICS_TEMPORALITY_PREFERENCE, "cumulative");

    prop.setProperty(TRACE_OTEL_ENABLED, "True");
    prop.setProperty(OTLP_TRACES_ENDPOINT, "http://localhost:4333/v1/traces");
    prop.setProperty(
        OTLP_TRACES_HEADERS, "api-key=key,other-config-value=value,traces-config-value=T");
    prop.setProperty(OTLP_TRACES_PROTOCOL, "http/protobuf");
    prop.setProperty(OTLP_TRACES_TIMEOUT, "5002");

    Config config = Config.get(prop);

    assertEquals("no config file present", config.getConfigFileStatus());
    // we can still override via internal properties object
    assertEquals("new api key", config.getApiKey());
    assertEquals("new site", config.getSite());
    assertEquals("something else", config.getServiceName());
    assertTrue(config.getIdGenerationStrategy().getClass().getName().endsWith("$Sequential"));
    assertFalse(config.isTraceEnabled());
    assertEquals("LoggingWriter", config.getWriterType());
    assertEquals("somehost", config.getAgentHost());
    assertEquals(123, config.getAgentPort());
    assertEquals("somepath", config.getAgentUnixDomainSocket());
    assertEquals("http://somehost:123", config.getAgentUrl());
    assertFalse(config.isPrioritySamplingEnabled());
    assertFalse(config.isTraceResolverEnabled());
    assertEquals(singletonMap("a", "1:2"), config.getServiceMapping());
    assertEquals(mapOf("b", "2", "c", "3"), config.getMergedSpanTags());
    assertEquals(
        mapOf(
            "b",
            "2",
            "d",
            "4",
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            config.getServiceName()),
        config.getMergedJmxTags());
    assertEquals(singletonMap("e", "five"), config.getRequestHeaderTags());
    assertEquals(mapOf("f", "six", "g", "g"), config.getBaggageMapping());
    assertEquals(toBitSet(122, 457), config.getHttpServerErrorStatuses());
    assertEquals(toBitSet(111, 111), config.getHttpClientErrorStatuses());
    assertTrue(config.isHttpClientSplitByDomain());
    assertTrue(config.isDbClientSplitByInstance());
    assertTrue(config.isDbClientSplitByInstanceTypeSuffix());
    assertTrue(config.isDbClientSplitByHost());
    assertEquals(new HashSet<>(asList("some.tag1", "some.tag2")), config.getSplitByTags());
    assertEquals(15, config.getPartialFlushMinSpans());
    assertTrue(config.isReportHostName());
    assertEquals(
        asList(PropagationStyle.DATADOG, PropagationStyle.B3),
        new ArrayList<>(config.getPropagationStylesToExtract()));
    assertEquals(
        asList(PropagationStyle.B3, PropagationStyle.DATADOG),
        new ArrayList<>(config.getPropagationStylesToInject()));
    assertEquals(
        asList(DATADOG, B3SINGLE, B3MULTI),
        new ArrayList<>(config.getTracePropagationStylesToExtract()));
    assertEquals(
        asList(B3SINGLE, B3MULTI, DATADOG),
        new ArrayList<>(config.getTracePropagationStylesToInject()));
    assertFalse(config.isTracePropagationExtractFirst());
    assertEquals(RESTART, config.getTracePropagationBehaviorExtract());
    assertFalse(config.isJmxFetchEnabled());
    assertEquals(asList("/foo.yaml", "/bar.yaml"), config.getJmxFetchMetricsConfigs());
    assertEquals(100, config.getJmxFetchCheckPeriod());
    assertEquals(200, config.getJmxFetchRefreshBeansPeriod());
    assertEquals("statsd host", config.getJmxFetchStatsdHost());
    assertEquals(321, config.getJmxFetchStatsdPort());

    assertTrue(config.isHealthMetricsEnabled());
    assertEquals("metrics statsd host", config.getHealthMetricsStatsdHost());
    assertEquals(654, config.getHealthMetricsStatsdPort());
    assertEquals(singletonMap("a", "1"), config.getTraceSamplingServiceRules());
    assertEquals(singletonMap("b", "1"), config.getTraceSamplingOperationRules());
    assertEquals(0.5, config.getTraceSampleRate());
    assertEquals(200, config.getTraceRateLimit());
    assertTrue(config.isLongRunningTraceEnabled());
    assertEquals(250, config.getLongRunningTraceFlushInterval());

    assertEquals(
        new HashSet<>(asList("DD_TAGS", "DD_TRACE_HTTP_CLIENT_TAG_QUERY_STRING")),
        config.getExperimentalFeaturesEnabled());

    assertTrue(config.isProfilingEnabled());
    // the configured profiling url is used as-is
    assertEquals("new url", config.getFinalProfilingUrl());
    assertEquals(
        mapOf(
            "b",
            "2",
            "f",
            "6",
            HOST_TAG,
            "test-host",
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            RUNTIME_VERSION_TAG,
            config.getRuntimeVersion(),
            SERVICE_TAG,
            config.getServiceName(),
            LANGUAGE_TAG_KEY,
            LANGUAGE_TAG_VALUE),
        config.getMergedProfilingTags());
    assertEquals(1111, config.getProfilingStartDelay());
    assertTrue(config.isProfilingStartForceFirst());
    assertEquals(1112, config.getProfilingUploadPeriod());
    assertEquals("off", config.getProfilingUploadCompression());
    assertEquals("/path", config.getProfilingTemplateOverrideFile());
    assertEquals(1116, config.getProfilingUploadTimeout());
    assertEquals("proxy-host", config.getProfilingProxyHost());
    assertEquals(1118, config.getProfilingProxyPort());
    assertEquals("proxy-username", config.getProfilingProxyUsername());
    assertEquals("proxy-password", config.getProfilingProxyPassword());
    assertEquals(811, config.getProfilingExceptionSampleLimit());
    assertEquals(1121, config.getProfilingExceptionHistogramTopItems());
    assertEquals(1122, config.getProfilingExceptionHistogramMaxCollectionSize());
    assertTrue(config.isProfilingAgentless());

    assertTrue(config.isRemoteConfigEnabled());
    assertEquals("remote config url", config.getFinalRemoteConfigUrl());
    assertEquals(3f, config.getRemoteConfigPollIntervalSeconds());
    assertEquals(2048L, config.getRemoteConfigMaxPayloadSizeBytes());

    assertTrue(config.isDynamicInstrumentationEnabled());
    assertEquals("http://somehost:123/debugger/v1/input", config.getFinalDebuggerSnapshotUrl());
    assertEquals("file location", config.getDynamicInstrumentationProbeFile());
    assertEquals(10, config.getDynamicInstrumentationUploadTimeout());
    assertEquals(234, config.getDynamicInstrumentationUploadFlushInterval());
    assertEquals(200, config.getDynamicInstrumentationUploadBatchSize());
    assertFalse(config.isDynamicInstrumentationMetricsEnabled());
    assertTrue(config.isDynamicInstrumentationClassFileDumpEnabled());
    assertEquals(10, config.getDynamicInstrumentationPollInterval());
    assertEquals(60, config.getDynamicInstrumentationDiagnosticsInterval());
    assertTrue(config.isDynamicInstrumentationVerifyByteCode());
    assertEquals("method", config.getDynamicInstrumentationInstrumentTheWorld());
    assertEquals("exclude file", config.getDynamicInstrumentationExcludeFiles());
    assertTrue(config.isDebuggerExceptionEnabled());
    assertEquals(128, config.getxDatadogTagsMaxLength());
    assertFalse(config.isJdkSocketEnabled());

    assertEquals(emptyList(), config.getCloudRequestPayloadTagging());
    assertEquals(emptyList(), config.getCloudResponsePayloadTagging());

    assertFalse(config.isSfnInjectDatadogAttributeEnabled());
    assertFalse(config.isSqsInjectDatadogAttributeEnabled());
    assertFalse(config.isSnsInjectDatadogAttributeEnabled());
    assertFalse(config.isEventbridgeInjectDatadogAttributeEnabled());

    assertEquals(128, config.getxDatadogTagsMaxLength());

    assertTrue(config.isMetricsOtelEnabled());
    assertEquals(11000, config.getMetricsOtelInterval());
    assertEquals(9000, config.getMetricsOtelTimeout());
    assertEquals("http://localhost:4333/v1/metrics", config.getOtlpMetricsEndpoint());
    assertEquals("key", config.getOtlpMetricsHeaders().get("api-key"));
    assertEquals("value", config.getOtlpMetricsHeaders().get("other-config-value"));
    assertEquals("M", config.getOtlpMetricsHeaders().get("metrics-config-value"));
    assertEquals(HTTP_PROTOBUF, config.getOtlpMetricsProtocol());
    assertEquals(5001, config.getOtlpMetricsTimeout());
    assertEquals(CUMULATIVE, config.getOtlpMetricsTemporalityPreference());

    assertTrue(config.isTraceOtelEnabled());
    assertEquals("http://localhost:4333/v1/traces", config.getOtlpTracesEndpoint());
    assertEquals("key", config.getOtlpTracesHeaders().get("api-key"));
    assertEquals("value", config.getOtlpTracesHeaders().get("other-config-value"));
    assertEquals("T", config.getOtlpTracesHeaders().get("traces-config-value"));
    assertEquals(HTTP_PROTOBUF, config.getOtlpTracesProtocol());
    assertEquals(5002, config.getOtlpTracesTimeout());
  }

  @Test
  void otelDefaultValuesWhenConfiguredIncorrectly() {
    Properties prop = new Properties();

    prop.setProperty(METRICS_OTEL_ENABLED, "youhou");
    prop.setProperty(METRICS_OTEL_INTERVAL, "-1");
    prop.setProperty(METRICS_OTEL_TIMEOUT, "invalid");
    prop.setProperty(OTLP_METRICS_ENDPOINT, "invalid");
    prop.setProperty(OTLP_METRICS_HEADERS, "11");
    prop.setProperty(OTLP_METRICS_PROTOCOL, "invalid");
    prop.setProperty(OTLP_METRICS_TIMEOUT, "-34");
    prop.setProperty(OTLP_METRICS_TEMPORALITY_PREFERENCE, "invalid");

    prop.setProperty(TRACE_OTEL_ENABLED, "youhou");
    prop.setProperty(OTLP_TRACES_ENDPOINT, "invalid");
    prop.setProperty(OTLP_TRACES_HEADERS, "11");
    prop.setProperty(OTLP_TRACES_PROTOCOL, "invalid");
    prop.setProperty(OTLP_TRACES_TIMEOUT, "-34");

    Config config = Config.get(prop);

    assertFalse(config.isMetricsOtelEnabled());
    assertEquals(10000, config.getMetricsOtelInterval());
    assertEquals(7500, config.getMetricsOtelTimeout());
    assertEquals("invalid", config.getOtlpMetricsEndpoint());
    assertEquals(emptyMap(), config.getOtlpMetricsHeaders());
    assertEquals(HTTP_PROTOBUF, config.getOtlpMetricsProtocol());
    assertEquals(7500, config.getOtlpMetricsTimeout());
    assertEquals(DELTA, config.getOtlpMetricsTemporalityPreference());

    assertFalse(config.isTraceOtelEnabled());
    assertEquals("invalid", config.getOtlpTracesEndpoint());
    assertEquals(emptyMap(), config.getOtlpTracesHeaders());
    assertEquals(HTTP_PROTOBUF, config.getOtlpTracesProtocol());
    assertEquals(10000, config.getOtlpTracesTimeout());
  }

  @Test
  void otelDefaultValuesWhenNotSet() {
    Properties prop = new Properties();

    Config config = Config.get(prop);

    assertFalse(config.isMetricsOtelEnabled());
    assertEquals(10000, config.getMetricsOtelInterval());
    assertEquals(7500, config.getMetricsOtelTimeout());
    assertEquals("http://localhost:4318/v1/metrics", config.getOtlpMetricsEndpoint());
    assertEquals(emptyMap(), config.getOtlpMetricsHeaders());
    assertEquals(HTTP_PROTOBUF, config.getOtlpMetricsProtocol());
    assertEquals(7500, config.getOtlpMetricsTimeout());
    assertEquals(DELTA, config.getOtlpMetricsTemporalityPreference());

    assertFalse(config.isTraceOtelEnabled());
    assertEquals("http://localhost:4318/v1/traces", config.getOtlpTracesEndpoint());
    assertEquals(emptyMap(), config.getOtlpTracesHeaders());
    assertEquals(HTTP_PROTOBUF, config.getOtlpTracesProtocol());
    assertEquals(10000, config.getOtlpTracesTimeout());

    assertFalse(config.isOtelTracesSpanMetricsEnabled());
    assertFalse(config.isTraceOtelSemanticsEnabled());
    assertEquals(10000, config.getTraceStatsInterval());
  }

  @Test
  void otelSemanticsForcesItsRequiredConfiguration() {
    ConfigCollector.get().collect();
    Properties prop = new Properties();
    prop.setProperty(TRACE_OTEL_SEMANTICS_ENABLED, "true");
    prop.setProperty(TRACE_OTEL_EXPORTER, "agent");
    prop.setProperty(WRITER_TYPE, "LoggingWriter");
    prop.setProperty(TRACE_SPAN_ATTRIBUTE_SCHEMA, "v1");
    prop.setProperty(TRACE_PEER_SERVICE_DEFAULTS_ENABLED, "true");

    Config config = Config.get(prop);
    Map<String, ConfigSetting> calculated =
        ConfigCollector.get().collect().get(ConfigOrigin.CALCULATED);

    assertTrue(config.isTraceOtelSemanticsEnabled());
    assertTrue(config.isTraceOtlpExporterEnabled());
    assertEquals("OtlpWriter", config.getWriterType());
    assertEquals(0, config.getSpanAttributeSchemaVersion());
    assertFalse(config.isPeerServiceDefaultsEnabled());
    assertEquals(toBitSet(400, 599), config.getHttpClientErrorStatuses());

    assertEquals("otlp", calculated.get(TRACE_OTEL_EXPORTER).value);
    assertEquals("OtlpWriter", calculated.get(WRITER_TYPE).value);
    assertEquals("v0", calculated.get(TRACE_SPAN_ATTRIBUTE_SCHEMA).value);
    assertEquals(false, calculated.get(TRACE_PEER_SERVICE_DEFAULTS_ENABLED).value);
  }

  @Test
  void otelCheckSyntaxForOtlpHeaders() {
    Properties prop = new Properties();
    prop.setProperty(OTLP_METRICS_HEADERS, "api,key=key");
    prop.setProperty(OTLP_TRACES_HEADERS, "api,key=key");

    Config config = Config.get(prop);

    assertEquals(0, config.getOtlpMetricsHeaders().size());
    assertEquals(0, config.getOtlpTracesHeaders().size());
  }

  @Test
  void otelGenericConfigViaSystemPropertiesMetricsEnabled() throws Exception {
    System.setProperty(DD_METRICS_OTEL_ENABLED_PROP, "true");
    System.setProperty(
        OTEL_RESOURCE_ATTRIBUTES_PROP,
        "service.name=my=app,service.version=1.0.0,deployment.environment.name=production, message=blahblah");
    System.setProperty("otel.log.level", "warning");

    Config config = newConfig();

    assertOtelGenericConfig(config, "warning");
  }

  @Test
  void otelGenericConfigViaSystemPropertiesTraceEnabled() throws Exception {
    System.setProperty(DD_TRACE_OTEL_ENABLED_PROP, "true");
    System.setProperty(
        OTEL_RESOURCE_ATTRIBUTES_PROP,
        "service.name=my=app,service.version=1.0.0,deployment.environment=production, message=blahblah");
    System.setProperty("otel.log.level", "warning");

    Config config = newConfig();

    assertOtelGenericConfig(config, "warning");
  }

  @Test
  void otelGenericConfigViaEnvVarMetricsEnabled() throws Exception {
    setEnv(DD_METRICS_OTEL_ENABLED_ENV, "true");
    setEnv(
        OTEL_RESOURCE_ATTRIBUTES_ENV,
        "service.name=my=app,service.version=1.0.0,deployment.environment=production, message=blahblah");
    setEnv("OTEL_LOG_LEVEL", "error");

    Config config = newConfig();

    assertOtelGenericConfig(config, "error");
  }

  @Test
  void otelGenericConfigViaEnvVarTracesEnabled() throws Exception {
    setEnv(DD_TRACE_OTEL_ENABLED_ENV, "true");
    setEnv(
        OTEL_RESOURCE_ATTRIBUTES_ENV,
        "service.name=my=app,service.version=1.0.0,deployment.environment=production, message=blahblah");
    setEnv("OTEL_LOG_LEVEL", "error");

    Config config = newConfig();

    assertOtelGenericConfig(config, "error");
  }

  private static void assertOtelGenericConfig(Config config, String expectedLogLevel) {
    assertEquals("my=app", config.getServiceName());
    assertEquals("1.0.0", config.getVersion());
    assertEquals("production", config.getEnv());
    Map<String, String> tags = config.getGlobalTags();
    assertEquals(3, tags.size());
    assertEquals("blahblah", tags.get("message"));
    assertEquals("production", tags.get("env"));
    assertEquals("1.0.0", tags.get("version"));
    assertEquals(expectedLogLevel, config.getLogLevel());
  }

  @Test
  void otelOtlpFallbackKeys() throws Exception {
    System.setProperty(DD_METRICS_OTEL_ENABLED_PROP, "true");
    System.setProperty(DD_TRACE_OTEL_ENABLED_PROP, "true");
    System.setProperty(OTEL_TRACES_EXPORTER_PROP, "otlp");
    System.setProperty(OTEL_EXPORTER_OTLP_PROTOCOL_PROP, "http/json");
    System.setProperty(OTEL_EXPORTER_OTLP_ENDPOINT_PROP, "http://localhost:4319");
    System.setProperty(OTEL_EXPORTER_OTLP_HEADERS_PROP, "api-key=key,other-config-value=value");
    System.setProperty(OTEL_EXPORTER_OTLP_TIMEOUT_PROP, "1000");

    Config config = newConfig();

    assertEquals(HTTP_JSON, config.getOtlpMetricsProtocol());
    assertEquals("http://localhost:4319/v1/metrics", config.getOtlpMetricsEndpoint());
    assertEquals(2, config.getOtlpMetricsHeaders().size());
    assertEquals("key", config.getOtlpMetricsHeaders().get("api-key"));
    assertEquals("value", config.getOtlpMetricsHeaders().get("other-config-value"));
    assertEquals(1000, config.getOtlpMetricsTimeout());

    assertEquals(HTTP_JSON, config.getOtlpTracesProtocol());
    assertEquals("http://localhost:4319/v1/traces", config.getOtlpTracesEndpoint());
    assertEquals(2, config.getOtlpTracesHeaders().size());
    assertEquals("key", config.getOtlpTracesHeaders().get("api-key"));
    assertEquals("value", config.getOtlpTracesHeaders().get("other-config-value"));
    assertEquals(1000, config.getOtlpTracesTimeout());
  }

  @Test
  void otelOtlpFallbackKeyEndpoint() throws Exception {
    System.setProperty(DD_METRICS_OTEL_ENABLED_PROP, "true");
    System.setProperty(DD_TRACE_OTEL_ENABLED_PROP, "true");
    System.setProperty(OTEL_TRACES_EXPORTER_PROP, "otlp");
    System.setProperty(OTEL_EXPORTER_OTLP_PROTOCOL_PROP, "http/json");
    System.setProperty(PREFIX + TRACE_AGENT_URL, "http://192.168.0.3:8126");

    Config config = newConfig();

    assertEquals("192.168.0.3", config.getAgentHost());
    assertEquals(HTTP_JSON, config.getOtlpMetricsProtocol());
    assertEquals("http://192.168.0.3:4318/v1/metrics", config.getOtlpMetricsEndpoint());
    assertEquals(HTTP_JSON, config.getOtlpTracesProtocol());
    assertEquals("http://192.168.0.3:4318/v1/traces", config.getOtlpTracesEndpoint());
  }

  @Test
  void otelOtlpFallbackKeyEndpoint2() throws Exception {
    System.setProperty(DD_METRICS_OTEL_ENABLED_PROP, "true");
    System.setProperty(DD_TRACE_OTEL_ENABLED_PROP, "true");
    System.setProperty(OTEL_TRACES_EXPORTER_PROP, "otlp");
    System.setProperty(PREFIX + TRACE_AGENT_URL, "'/tmp/ddagent/trace.sock'");

    Config config = newConfig();

    assertEquals("localhost", config.getAgentHost());
    assertEquals(HTTP_PROTOBUF, config.getOtlpMetricsProtocol());
    assertEquals("http://localhost:4318/v1/metrics", config.getOtlpMetricsEndpoint());
    assertEquals(HTTP_PROTOBUF, config.getOtlpTracesProtocol());
    assertEquals("http://localhost:4318/v1/traces", config.getOtlpTracesEndpoint());
  }

  @TableTest({
    "scenario                                    | exporter | metricsEnabled | metricsExporter | override | expected",
    "metrics defaults to otlp                    | otlp     | true           |                 |          | true    ",
    "otlp traces and metrics                     | otlp     | true           | otlp            |          | true    ",
    "metrics exporter explicitly disabled        | otlp     | true           | none            |          | false   ",
    "metrics feature flag off overrides exporter | otlp     | false          | otlp            |          | false   ",
    "no trace export, default metrics exporter   | none     | true           |                 |          | false   ",
    "no trace export, otlp metrics exporter      | none     | true           | otlp            |          | false   ",
    "explicit override wins (enabled)            | none     | false          | none            | true     | true    ",
    "explicit override wins (disabled)           | otlp     | true           | otlp            | false    | false   "
  })
  void tracesSpanMetricsTriStateDefault(
      String exporter,
      String metricsEnabled,
      String metricsExporter,
      String override,
      boolean expected)
      throws Exception {
    System.setProperty(PREFIX + TRACE_OTEL_EXPORTER, exporter);
    System.setProperty(PREFIX + METRICS_OTEL_ENABLED, metricsEnabled);
    if (metricsExporter != null) {
      System.setProperty(PREFIX + METRICS_OTEL_EXPORTER, metricsExporter);
    }
    if (override != null) {
      System.setProperty(PREFIX + OTEL_TRACES_SPAN_METRICS_ENABLED, override);
    }

    Config config = newConfig();

    // Unset: emit iff OTLP trace export and OTLP metrics export are both on. An explicit
    // dd.otel.traces.span.metrics.enabled always wins.
    assertEquals(expected, config.isOtelTracesSpanMetricsEnabled());
  }

  @Test
  void specifyOverridesViaSystemProperties() throws Exception {
    System.setProperty(PREFIX + API_KEY, "new api key");
    System.setProperty(PREFIX + SITE, "new site");
    System.setProperty(PREFIX + SERVICE_NAME, "something else");
    System.setProperty(PREFIX + TRACE_ENABLED, "false");
    System.setProperty(PREFIX + WRITER_TYPE, "LoggingWriter");
    System.setProperty(PREFIX + PRIORITIZATION_TYPE, "EnsureTrace");
    System.setProperty(PREFIX + AGENT_HOST, "somehost");
    System.setProperty(PREFIX + TRACE_AGENT_PORT, "123");
    System.setProperty(PREFIX + AGENT_UNIX_DOMAIN_SOCKET, "somepath");
    System.setProperty(PREFIX + AGENT_PORT_LEGACY, "456");
    System.setProperty(PREFIX + PRIORITY_SAMPLING, "false");
    System.setProperty(PREFIX + TRACE_RESOLVER_ENABLED, "false");
    System.setProperty(PREFIX + SERVICE_MAPPING, "a:1");
    System.setProperty(PREFIX + GLOBAL_TAGS, "b:2");
    System.setProperty(PREFIX + SPAN_TAGS, "c:3");
    System.setProperty(PREFIX + JMX_TAGS, "d:4");
    System.setProperty(PREFIX + HEADER_TAGS, "e:five");
    System.setProperty(PREFIX + BAGGAGE_MAPPING, "f:six,g");
    System.setProperty(PREFIX + HTTP_SERVER_ERROR_STATUSES, "123-456,457,124-125,122");
    System.setProperty(PREFIX + HTTP_CLIENT_ERROR_STATUSES, "111");
    System.setProperty(PREFIX + HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true");
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true");
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, "true");
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_HOST, "true");
    System.setProperty(PREFIX + SPLIT_BY_TAGS, "some.tag3, some.tag2, some.tag1");
    System.setProperty(PREFIX + PARTIAL_FLUSH_MIN_SPANS, "25");
    System.setProperty(PREFIX + TRACE_REPORT_HOSTNAME, "true");
    System.setProperty(PREFIX + RUNTIME_CONTEXT_FIELD_INJECTION, "false");
    System.setProperty(PREFIX + PROPAGATION_STYLE_EXTRACT, "Datadog, B3");
    System.setProperty(PREFIX + PROPAGATION_STYLE_INJECT, "B3, Datadog");
    System.setProperty(PREFIX + TRACE_PROPAGATION_EXTRACT_FIRST, "false");
    System.setProperty(PREFIX + TRACE_PROPAGATION_BEHAVIOR_EXTRACT, "restart");
    System.setProperty(PREFIX + JMX_FETCH_ENABLED, "false");
    System.setProperty(PREFIX + JMX_FETCH_METRICS_CONFIGS, "/foo.yaml,/bar.yaml");
    System.setProperty(PREFIX + JMX_FETCH_CHECK_PERIOD, "100");
    System.setProperty(PREFIX + JMX_FETCH_REFRESH_BEANS_PERIOD, "200");
    System.setProperty(PREFIX + JMX_FETCH_STATSD_HOST, "statsd host");
    System.setProperty(PREFIX + JMX_FETCH_STATSD_PORT, "321");
    System.setProperty(PREFIX + HEALTH_METRICS_ENABLED, "true");
    System.setProperty(PREFIX + HEALTH_METRICS_STATSD_HOST, "metrics statsd host");
    System.setProperty(PREFIX + HEALTH_METRICS_STATSD_PORT, "654");
    System.setProperty(PREFIX + TRACE_SAMPLING_SERVICE_RULES, "a:1");
    System.setProperty(PREFIX + TRACE_SAMPLING_OPERATION_RULES, "b:1");
    System.setProperty(PREFIX + TRACE_SAMPLE_RATE, ".5");
    System.setProperty(PREFIX + TRACE_RATE_LIMIT, "200");
    System.setProperty(PREFIX + TRACE_LONG_RUNNING_ENABLED, "true");
    System.setProperty(PREFIX + TRACE_LONG_RUNNING_FLUSH_INTERVAL, "333");

    System.setProperty(
        PREFIX + TRACE_EXPERIMENTAL_FEATURES_ENABLED,
        "DD_TAGS, DD_TRACE_HTTP_CLIENT_TAG_QUERY_STRING");

    System.setProperty(PREFIX + PROFILING_ENABLED, "true");
    System.setProperty(PREFIX + PROFILING_URL, "new url");
    System.setProperty(PREFIX + PROFILING_TAGS, "f:6,host:test-host");
    System.setProperty(PREFIX + PROFILING_START_DELAY, "1111");
    System.setProperty(PREFIX + PROFILING_START_FORCE_FIRST, "true");
    System.setProperty(PREFIX + PROFILING_UPLOAD_PERIOD, "1112");
    System.setProperty(PREFIX + PROFILING_TEMPLATE_OVERRIDE_FILE, "/path");
    System.setProperty(PREFIX + PROFILING_UPLOAD_TIMEOUT, "1116");
    System.setProperty(PREFIX + PROFILING_UPLOAD_COMPRESSION, "off");
    System.setProperty(PREFIX + PROFILING_PROXY_HOST, "proxy-host");
    System.setProperty(PREFIX + PROFILING_PROXY_PORT, "1118");
    System.setProperty(PREFIX + PROFILING_PROXY_USERNAME, "proxy-username");
    System.setProperty(PREFIX + PROFILING_PROXY_PASSWORD, "proxy-password");
    System.setProperty(PREFIX + PROFILING_EXCEPTION_SAMPLE_LIMIT, "811");
    System.setProperty(PREFIX + PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS, "1121");
    System.setProperty(PREFIX + PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE, "1122");
    System.setProperty(PREFIX + PROFILING_AGENTLESS, "true");

    System.setProperty(PREFIX + REMOTE_CONFIGURATION_ENABLED, "true");
    System.setProperty(PREFIX + REMOTE_CONFIG_URL, "remote config url");
    System.setProperty(PREFIX + REMOTE_CONFIG_POLL_INTERVAL_SECONDS, "3");
    System.setProperty(PREFIX + REMOTE_CONFIG_MAX_PAYLOAD_SIZE, "2");

    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_ENABLED, "true");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_SNAPSHOT_URL, "snapshot url");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_PROBE_FILE, "file location");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT, "10");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL, "1000");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE, "200");
    System.setProperty(PREFIX + REMOTE_CONFIG_MAX_PAYLOAD_SIZE, "2");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_METRICS_ENABLED, "false");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED, "true");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_POLL_INTERVAL, "10");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL, "60");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE, "true");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_INSTRUMENT_THE_WORLD, "method");
    System.setProperty(PREFIX + DYNAMIC_INSTRUMENTATION_EXCLUDE_FILES, "exclude file");
    System.setProperty(PREFIX + TRACE_X_DATADOG_TAGS_MAX_LENGTH, "128");

    System.setProperty(PREFIX + TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, "all");
    System.setProperty(PREFIX + TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, "all");

    System.setProperty(PREFIX + SFN_INJECT_DATADOG_ATTRIBUTE_ENABLED, "false");
    System.setProperty(PREFIX + EVENTBRIDGE_INJECT_DATADOG_ATTRIBUTE_ENABLED, "false");
    System.setProperty(PREFIX + SNS_INJECT_DATADOG_ATTRIBUTE_ENABLED, "false");
    System.setProperty(PREFIX + SQS_INJECT_DATADOG_ATTRIBUTE_ENABLED, "false");

    System.setProperty(DD_METRICS_OTEL_ENABLED_PROP, "True");
    System.setProperty(OTEL_METRIC_EXPORT_INTERVAL_PROP, "11000");
    System.setProperty(OTEL_METRIC_EXPORT_TIMEOUT_PROP, "9000");
    System.setProperty(
        OTEL_EXPORTER_OTLP_METRICS_ENDPOINT_PROP, "http://localhost:4333/v1/metrics");
    System.setProperty(
        OTEL_EXPORTER_OTLP_METRICS_HEADERS_PROP,
        "api-key=key,other-config-value=value,metrics-config-value=M");
    System.setProperty(OTEL_EXPORTER_OTLP_METRICS_PROTOCOL_PROP, "http/protobuf");
    System.setProperty(OTEL_EXPORTER_OTLP_METRICS_TIMEOUT_PROP, "5001");
    System.setProperty(OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE_PROP, "cumulative");

    System.setProperty(DD_TRACE_OTEL_ENABLED_PROP, "True");
    System.setProperty(OTEL_TRACES_EXPORTER_PROP, "otlp");
    System.setProperty(OTEL_EXPORTER_OTLP_TRACES_ENDPOINT_PROP, "http://localhost:4333/v1/traces");
    System.setProperty(
        OTEL_EXPORTER_OTLP_TRACES_HEADERS_PROP,
        "api-key=key,other-config-value=value,traces-config-value=T");
    System.setProperty(OTEL_EXPORTER_OTLP_TRACES_PROTOCOL_PROP, "http/protobuf");
    System.setProperty(OTEL_EXPORTER_OTLP_TRACES_TIMEOUT_PROP, "5002");
    System.setProperty(
        OTEL_RESOURCE_ATTRIBUTES_PROP,
        "service.name=my=app,service.version=1.0.0,deployment.environment=production");
    System.setProperty(PREFIX + TRACE_OTEL_SEMANTICS_ENABLED, "true");

    Config config = newConfig();

    // system properties cannot be used to provide a key
    assertNull(config.getApiKey());
    assertEquals("new site", config.getSite());
    assertEquals("something else", config.getServiceName());
    assertFalse(config.isTraceEnabled());
    assertEquals("OtlpWriter", config.getWriterType());
    assertEquals("somehost", config.getAgentHost());
    assertEquals(123, config.getAgentPort());
    assertEquals("somepath", config.getAgentUnixDomainSocket());
    assertEquals("http://somehost:123", config.getAgentUrl());
    assertFalse(config.isPrioritySamplingEnabled());
    assertFalse(config.isTraceResolverEnabled());
    assertEquals(singletonMap("a", "1"), config.getServiceMapping());
    assertEquals(
        mapOf("b", "2", "c", "3", "env", "production", "version", "1.0.0"),
        config.getMergedSpanTags());
    assertEquals(
        mapOf(
            "b",
            "2",
            "d",
            "4",
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            config.getServiceName(),
            "env",
            "production",
            "version",
            "1.0.0"),
        config.getMergedJmxTags());
    assertEquals(singletonMap("e", "five"), config.getRequestHeaderTags());
    assertEquals(mapOf("f", "six", "g", "g"), config.getBaggageMapping());
    assertEquals(toBitSet(122, 457), config.getHttpServerErrorStatuses());
    assertEquals(toBitSet(111, 111), config.getHttpClientErrorStatuses());
    assertTrue(config.isHttpClientSplitByDomain());
    assertTrue(config.isDbClientSplitByInstance());
    assertTrue(config.isDbClientSplitByInstanceTypeSuffix());
    assertTrue(config.isDbClientSplitByHost());
    assertEquals(
        new HashSet<>(asList("some.tag3", "some.tag2", "some.tag1")), config.getSplitByTags());
    assertEquals(25, config.getPartialFlushMinSpans());
    assertTrue(config.isReportHostName());
    assertEquals(
        asList(PropagationStyle.DATADOG, PropagationStyle.B3),
        new ArrayList<>(config.getPropagationStylesToExtract()));
    assertEquals(
        asList(PropagationStyle.B3, PropagationStyle.DATADOG),
        new ArrayList<>(config.getPropagationStylesToInject()));
    assertEquals(
        asList(DATADOG, B3SINGLE, B3MULTI),
        new ArrayList<>(config.getTracePropagationStylesToExtract()));
    assertEquals(
        asList(B3SINGLE, B3MULTI, DATADOG),
        new ArrayList<>(config.getTracePropagationStylesToInject()));
    assertFalse(config.isTracePropagationExtractFirst());
    assertEquals(RESTART, config.getTracePropagationBehaviorExtract());
    assertFalse(config.isJmxFetchEnabled());
    assertEquals(asList("/foo.yaml", "/bar.yaml"), config.getJmxFetchMetricsConfigs());
    assertEquals(100, config.getJmxFetchCheckPeriod());
    assertEquals(200, config.getJmxFetchRefreshBeansPeriod());
    assertEquals("statsd host", config.getJmxFetchStatsdHost());
    assertEquals(321, config.getJmxFetchStatsdPort());

    assertTrue(config.isHealthMetricsEnabled());
    assertEquals("metrics statsd host", config.getHealthMetricsStatsdHost());
    assertEquals(654, config.getHealthMetricsStatsdPort());
    assertEquals(singletonMap("a", "1"), config.getTraceSamplingServiceRules());
    assertEquals(singletonMap("b", "1"), config.getTraceSamplingOperationRules());
    assertEquals(0.5, config.getTraceSampleRate());
    assertEquals(200, config.getTraceRateLimit());
    assertTrue(config.isLongRunningTraceEnabled());
    assertEquals(333, config.getLongRunningTraceFlushInterval());
    assertEquals(200, config.getTraceRateLimit());

    assertEquals(
        new HashSet<>(asList("DD_TAGS", "DD_TRACE_HTTP_CLIENT_TAG_QUERY_STRING")),
        config.getExperimentalFeaturesEnabled());

    assertTrue(config.isProfilingEnabled());
    // the configured profiling url is used as-is
    assertEquals("new url", config.getFinalProfilingUrl());
    assertEquals(
        mapOf(
            "b",
            "2",
            "f",
            "6",
            HOST_TAG,
            "test-host",
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            RUNTIME_VERSION_TAG,
            config.getRuntimeVersion(),
            SERVICE_TAG,
            config.getServiceName(),
            LANGUAGE_TAG_KEY,
            LANGUAGE_TAG_VALUE,
            "env",
            "production",
            "version",
            "1.0.0"),
        config.getMergedProfilingTags());
    assertEquals(1111, config.getProfilingStartDelay());
    assertTrue(config.isProfilingStartForceFirst());
    assertEquals(1112, config.getProfilingUploadPeriod());
    assertEquals("/path", config.getProfilingTemplateOverrideFile());
    assertEquals(1116, config.getProfilingUploadTimeout());
    assertEquals("off", config.getProfilingUploadCompression());
    assertEquals("proxy-host", config.getProfilingProxyHost());
    assertEquals(1118, config.getProfilingProxyPort());
    assertEquals("proxy-username", config.getProfilingProxyUsername());
    assertEquals("proxy-password", config.getProfilingProxyPassword());
    assertEquals(811, config.getProfilingExceptionSampleLimit());
    assertEquals(1121, config.getProfilingExceptionHistogramTopItems());
    assertEquals(1122, config.getProfilingExceptionHistogramMaxCollectionSize());
    assertTrue(config.isProfilingAgentless());

    assertTrue(config.isRemoteConfigEnabled());
    assertEquals("remote config url", config.getFinalRemoteConfigUrl());
    assertEquals(3f, config.getRemoteConfigPollIntervalSeconds());
    assertEquals(2 * 1024L, config.getRemoteConfigMaxPayloadSizeBytes());

    assertTrue(config.isDynamicInstrumentationEnabled());
    assertEquals("file location", config.getDynamicInstrumentationProbeFile());
    assertEquals(10, config.getDynamicInstrumentationUploadTimeout());
    assertEquals(1000, config.getDynamicInstrumentationUploadFlushInterval());
    assertEquals(200, config.getDynamicInstrumentationUploadBatchSize());
    assertFalse(config.isDynamicInstrumentationMetricsEnabled());
    assertTrue(config.isDynamicInstrumentationClassFileDumpEnabled());
    assertEquals(10, config.getDynamicInstrumentationPollInterval());
    assertEquals(60, config.getDynamicInstrumentationDiagnosticsInterval());
    assertTrue(config.isDynamicInstrumentationVerifyByteCode());
    assertEquals("method", config.getDynamicInstrumentationInstrumentTheWorld());
    assertEquals("exclude file", config.getDynamicInstrumentationExcludeFiles());

    assertEquals(emptyList(), config.getCloudRequestPayloadTagging());
    assertEquals(emptyList(), config.getCloudResponsePayloadTagging());

    assertFalse(config.isSfnInjectDatadogAttributeEnabled());
    assertFalse(config.isSqsInjectDatadogAttributeEnabled());
    assertFalse(config.isSnsInjectDatadogAttributeEnabled());
    assertFalse(config.isEventbridgeInjectDatadogAttributeEnabled());

    assertEquals(128, config.getxDatadogTagsMaxLength());

    assertTrue(config.isMetricsOtelEnabled());
    assertEquals("1.0.0", config.getVersion());
    assertEquals("production", config.getEnv());
    assertEquals(11000, config.getMetricsOtelInterval());
    assertEquals(9000, config.getMetricsOtelTimeout());
    assertEquals("http://localhost:4333/v1/metrics", config.getOtlpMetricsEndpoint());
    assertEquals("key", config.getOtlpMetricsHeaders().get("api-key"));
    assertEquals("value", config.getOtlpMetricsHeaders().get("other-config-value"));
    assertEquals("M", config.getOtlpMetricsHeaders().get("metrics-config-value"));
    assertEquals(HTTP_PROTOBUF, config.getOtlpMetricsProtocol());
    assertEquals(5001, config.getOtlpMetricsTimeout());
    assertEquals(CUMULATIVE, config.getOtlpMetricsTemporalityPreference());

    assertTrue(config.isTraceOtelEnabled());
    assertEquals("http://localhost:4333/v1/traces", config.getOtlpTracesEndpoint());
    assertEquals("key", config.getOtlpTracesHeaders().get("api-key"));
    assertEquals("value", config.getOtlpTracesHeaders().get("other-config-value"));
    assertEquals("T", config.getOtlpTracesHeaders().get("traces-config-value"));
    assertEquals(HTTP_PROTOBUF, config.getOtlpTracesProtocol());
    assertEquals(5002, config.getOtlpTracesTimeout());

    assertTrue(config.isTraceOtelSemanticsEnabled());
    // tri-state default: OTLP trace export + OTel metrics both on
    assertTrue(config.isOtelTracesSpanMetricsEnabled());
  }

  @Test
  void specifyOverridesViaEnvVars() throws Exception {
    setEnv(DD_API_KEY_ENV, "test-api-key");
    setEnv(DD_SERVICE_NAME_ENV, "still something else");
    setEnv(DD_TRACE_ENABLED_ENV, "false");
    setEnv(DD_WRITER_TYPE_ENV, "LoggingWriter");
    setEnv(DD_PRIORITIZATION_TYPE_ENV, "EnsureTrace");
    setEnv(DD_PROPAGATION_STYLE_EXTRACT, "B3 Datadog");
    setEnv(DD_PROPAGATION_STYLE_INJECT, "Datadog B3");
    setEnv(DD_TRACE_PROPAGATION_EXTRACT_FIRST, "false");
    setEnv(DD_JMXFETCH_METRICS_CONFIGS_ENV, "some/file");
    setEnv(DD_TRACE_REPORT_HOSTNAME, "true");
    setEnv(DD_TRACE_X_DATADOG_TAGS_MAX_LENGTH, "42");
    setEnv(DD_TRACE_LONG_RUNNING_ENABLED, "true");
    setEnv(DD_TRACE_LONG_RUNNING_FLUSH_INTERVAL, "81");
    setEnv(DD_TRACE_HEADER_TAGS, "*");
    setEnv(DD_TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING_ENV, "all");
    setEnv(DD_TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING_ENV, "all");
    setEnv(DD_SFN_INJECT_DATADOG_ATTRIBUTE_ENABLED_ENV, "false");
    setEnv(DD_SQS_INJECT_DATADOG_ATTRIBUTE_ENABLED_ENV, "false");
    setEnv(DD_SNS_INJECT_DATADOG_ATTRIBUTE_ENABLED_ENV, "false");
    setEnv(DD_EVENTBRIDGE_INJECT_DATADOG_ATTRIBUTE_ENABLED_ENV, "false");

    setEnv(DD_METRICS_OTEL_ENABLED_ENV, "True");
    setEnv(
        OTEL_RESOURCE_ATTRIBUTES_ENV,
        "service.name=my=app,service.version=1.0.0,deployment.environment=production");
    setEnv(OTEL_METRIC_EXPORT_INTERVAL_ENV, "11000");
    setEnv(OTEL_METRIC_EXPORT_TIMEOUT_ENV, "9000");
    setEnv(OTEL_EXPORTER_OTLP_METRICS_ENDPOINT_ENV, "http://localhost:4333/v1/metrics");
    setEnv(
        OTEL_EXPORTER_OTLP_METRICS_HEADERS_ENV,
        "api-key=key,other-config-value=value,metrics-config-value=M");
    setEnv(OTEL_EXPORTER_OTLP_METRICS_PROTOCOL_ENV, "http/protobuf");
    setEnv(OTEL_EXPORTER_OTLP_METRICS_TIMEOUT_ENV, "5001");
    setEnv(OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE_ENV, "cumulative");

    setEnv(DD_TRACE_OTEL_ENABLED_ENV, "True");
    setEnv(OTEL_TRACES_EXPORTER_ENV, "otlp");
    setEnv(OTEL_EXPORTER_OTLP_TRACES_ENDPOINT_ENV, "http://localhost:4333/v1/traces");
    setEnv(
        OTEL_EXPORTER_OTLP_TRACES_HEADERS_ENV,
        "api-key=key,other-config-value=value,traces-config-value=T");
    setEnv(OTEL_EXPORTER_OTLP_TRACES_PROTOCOL_ENV, "http/protobuf");
    setEnv(OTEL_EXPORTER_OTLP_TRACES_TIMEOUT_ENV, "5002");

    Config config = newConfig();

    assertEquals("test-api-key", config.getApiKey());
    assertEquals("still something else", config.getServiceName());
    assertFalse(config.isTraceEnabled());
    assertEquals("LoggingWriter", config.getWriterType());
    assertEquals(
        asList(PropagationStyle.B3, PropagationStyle.DATADOG),
        new ArrayList<>(config.getPropagationStylesToExtract()));
    assertEquals(
        asList(PropagationStyle.DATADOG, PropagationStyle.B3),
        new ArrayList<>(config.getPropagationStylesToInject()));
    assertEquals(
        asList(B3SINGLE, B3MULTI, DATADOG),
        new ArrayList<>(config.getTracePropagationStylesToExtract()));
    assertEquals(
        asList(DATADOG, B3SINGLE, B3MULTI),
        new ArrayList<>(config.getTracePropagationStylesToInject()));
    assertFalse(config.isTracePropagationExtractFirst());
    assertEquals(singletonList("some/file"), config.getJmxFetchMetricsConfigs());
    assertTrue(config.isReportHostName());
    assertEquals(42, config.getxDatadogTagsMaxLength());
    assertTrue(config.isLongRunningTraceEnabled());
    assertEquals(81, config.getLongRunningTraceFlushInterval());
    assertEquals(emptyList(), config.getCloudRequestPayloadTagging());
    assertEquals(emptyList(), config.getCloudResponsePayloadTagging());
    assertFalse(config.isSfnInjectDatadogAttributeEnabled());
    assertFalse(config.isSqsInjectDatadogAttributeEnabled());
    assertFalse(config.isSnsInjectDatadogAttributeEnabled());
    assertFalse(config.isEventbridgeInjectDatadogAttributeEnabled());

    assertEquals(singletonMap("*", "http.request.headers."), config.getRequestHeaderTags());
    assertEquals(singletonMap("*", "http.response.headers."), config.getResponseHeaderTags());

    assertTrue(config.isMetricsOtelEnabled());
    assertEquals(11000, config.getMetricsOtelInterval());
    assertEquals(9000, config.getMetricsOtelTimeout());
    assertEquals("http://localhost:4333/v1/metrics", config.getOtlpMetricsEndpoint());
    Map<String, String> otlpMetricsHeaders = config.getOtlpMetricsHeaders();
    assertEquals("key", otlpMetricsHeaders.get("api-key"));
    assertEquals("value", otlpMetricsHeaders.get("other-config-value"));
    assertEquals("M", otlpMetricsHeaders.get("metrics-config-value"));
    assertEquals(HTTP_PROTOBUF, config.getOtlpMetricsProtocol());
    assertEquals(5001, config.getOtlpMetricsTimeout());
    assertEquals(CUMULATIVE, config.getOtlpMetricsTemporalityPreference());

    assertTrue(config.isTraceOtelEnabled());
    assertEquals("http://localhost:4333/v1/traces", config.getOtlpTracesEndpoint());
    Map<String, String> otlpTracesHeaders = config.getOtlpTracesHeaders();
    assertEquals("key", otlpTracesHeaders.get("api-key"));
    assertEquals("value", otlpTracesHeaders.get("other-config-value"));
    assertEquals("T", otlpTracesHeaders.get("traces-config-value"));
    assertEquals(HTTP_PROTOBUF, config.getOtlpTracesProtocol());
    assertEquals(5002, config.getOtlpTracesTimeout());
  }

  @Test
  void sysPropsOverrideEnvVars() throws Exception {
    setEnv(DD_SERVICE_NAME_ENV, "still something else");
    setEnv(DD_WRITER_TYPE_ENV, "LoggingWriter");
    setEnv(DD_PRIORITIZATION_TYPE_ENV, "EnsureTrace");
    setEnv(DD_TRACE_AGENT_PORT_ENV, "777");
    setEnv(DD_TRACE_LONG_RUNNING_ENABLED, "false");
    setEnv(DD_TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING_ENV, "all");
    setEnv(DD_TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING_ENV, "");

    System.setProperty(PREFIX + SERVICE_NAME, "what we actually want");
    System.setProperty(PREFIX + WRITER_TYPE, "DDAgentWriter");
    System.setProperty(PREFIX + PRIORITIZATION_TYPE, "FastLane");
    System.setProperty(PREFIX + AGENT_HOST, "somewhere");
    System.setProperty(PREFIX + TRACE_AGENT_PORT, "123");
    System.setProperty(PREFIX + TRACE_LONG_RUNNING_ENABLED, "true");
    System.setProperty(PREFIX + TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, "");
    System.setProperty(PREFIX + TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, "all");

    Config config = newConfig();

    assertEquals("what we actually want", config.getServiceName());
    assertEquals("DDAgentWriter", config.getWriterType());
    assertEquals("somewhere", config.getAgentHost());
    assertEquals(123, config.getAgentPort());
    assertEquals("http://somewhere:123", config.getAgentUrl());
    assertTrue(config.isLongRunningTraceEnabled());
    assertEquals(120, config.getLongRunningTraceFlushInterval());
    assertEquals(emptyList(), config.getCloudRequestPayloadTagging());
    assertEquals(emptyList(), config.getCloudResponsePayloadTagging());
    assertFalse(config.isCloudRequestPayloadTaggingEnabled());
    assertTrue(config.isCloudResponsePayloadTaggingEnabled());
  }

  @Test
  void defaultWhenConfiguredIncorrectly() throws Exception {
    System.setProperty(PREFIX + SERVICE_NAME, " ");
    System.setProperty(PREFIX + TRACE_ENABLED, " ");
    System.setProperty(PREFIX + WRITER_TYPE, " ");
    System.setProperty(PREFIX + PRIORITIZATION_TYPE, " ");
    System.setProperty(PREFIX + AGENT_HOST, " ");
    System.setProperty(PREFIX + TRACE_AGENT_PORT, " ");
    System.setProperty(PREFIX + AGENT_PORT_LEGACY, "invalid");
    System.setProperty(PREFIX + PRIORITY_SAMPLING, "3");
    System.setProperty(PREFIX + TRACE_RESOLVER_ENABLED, " ");
    System.setProperty(PREFIX + SERVICE_MAPPING, " ");
    System.setProperty(PREFIX + HEADER_TAGS, "1");
    System.setProperty(PREFIX + BAGGAGE_MAPPING, "1");
    System.setProperty(PREFIX + SPAN_TAGS, "invalid");
    System.setProperty(PREFIX + HTTP_SERVER_ERROR_STATUSES, "1111");
    System.setProperty(PREFIX + HTTP_CLIENT_ERROR_STATUSES, "1:1");
    System.setProperty(PREFIX + HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "invalid");
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "invalid");
    System.setProperty(PREFIX + DB_CLIENT_HOST_SPLIT_BY_HOST, "invalid");
    System.setProperty(PREFIX + PROPAGATION_STYLE_EXTRACT, "some garbage");
    System.setProperty(PREFIX + PROPAGATION_STYLE_INJECT, " ");
    System.setProperty(PREFIX + TRACE_LONG_RUNNING_ENABLED, "invalid");
    System.setProperty(PREFIX + TRACE_LONG_RUNNING_FLUSH_INTERVAL, "invalid");
    System.setProperty(PREFIX + TRACE_EXPERIMENTAL_FEATURES_ENABLED, " ");

    Config config = newConfig();

    assertEquals(" ", config.getServiceName());
    assertTrue(config.isTraceEnabled());
    assertEquals(" ", config.getWriterType());
    assertEquals(" ", config.getAgentHost());
    assertEquals(8126, config.getAgentPort());
    assertEquals("http:// :8126", config.getAgentUrl());
    assertFalse(config.isPrioritySamplingEnabled());
    assertTrue(config.isTraceResolverEnabled());
    assertEquals(emptyMap(), config.getServiceMapping());
    assertEquals(emptyMap(), config.getMergedSpanTags());
    assertEquals(emptyMap(), config.getRequestHeaderTags());
    assertEquals(emptyMap(), config.getBaggageMapping());
    assertEquals(toBitSet(500, 599), config.getHttpServerErrorStatuses());
    assertEquals(toBitSet(400, 499), config.getHttpClientErrorStatuses());
    assertFalse(config.isHttpClientSplitByDomain());
    assertFalse(config.isDbClientSplitByInstance());
    assertFalse(config.isDbClientSplitByInstanceTypeSuffix());
    assertFalse(config.isDbClientSplitByHost());
    assertEquals(emptySet(), config.getSplitByTags());
    assertEquals(
        singletonList(PropagationStyle.DATADOG),
        new ArrayList<>(config.getPropagationStylesToExtract()));
    assertEquals(
        singletonList(PropagationStyle.DATADOG),
        new ArrayList<>(config.getPropagationStylesToInject()));
    assertEquals(
        asList(DATADOG, TRACECONTEXT, BAGGAGE),
        new ArrayList<>(config.getTracePropagationStylesToExtract()));
    assertEquals(
        asList(DATADOG, TRACECONTEXT, BAGGAGE),
        new ArrayList<>(config.getTracePropagationStylesToInject()));
    assertFalse(config.isLongRunningTraceEnabled());
    assertEquals(emptySet(), config.getExperimentalFeaturesEnabled());
  }

  // spotless:off
  @TableTest({
    "scenario                                    | overridePort | overrideLegacyPort | overridePortEnvVar | overrideLegacyPortEnvVar | expectedPort",
    "port and legacy port                        | true         | true               | false              | false                    | 123",
    "port                                        | true         | false              | false              | false                    | 123",
    "legacy port                                 | false        | true               | false              | false                    | 456",
    "nothing                                     | false        | false              | false              | false                    | 8126",
    "port, legacy port and port env              | true         | true               | true               | false                    | 123",
    "port and port env                           | true         | false              | true               | false                    | 123",
    // legacy port gets picked up instead.
    "legacy port and port env                    | false        | true               | true               | false                    | 456",
    // env var gets picked up instead.
    "port env                                    | false        | false              | true               | false                    | 777",
    "port, legacy port and legacy env            | true         | true               | false              | true                     | 123",
    "port and legacy env                         | true         | false              | false              | true                     | 123",
    "legacy port and legacy env                  | false        | true               | false              | true                     | 456",
    // legacy env var gets picked up instead.
    "legacy env                                  | false        | false              | false              | true                     | 888",
    "port, legacy port, port env and legacy env  | true         | true               | true               | true                     | 123",
    "port, port env and legacy env               | true         | false              | true               | true                     | 123",
    // legacy port gets picked up instead.
    "legacy port, port env and legacy env        | false        | true               | true               | true                     | 456",
    // env var gets picked up instead.
    "port env and legacy env                     | false        | false              | true               | true                     | 777"
  })
  // spotless:on
  void sysPropsAndEnvVarsOverridesForTraceAgentPortAndAgentPortLegacyAsExpected(
      boolean overridePort,
      boolean overrideLegacyPort,
      boolean overridePortEnvVar,
      boolean overrideLegacyPortEnvVar,
      int expectedPort)
      throws Exception {
    if (overridePortEnvVar) {
      setEnv(DD_TRACE_AGENT_PORT_ENV, "777");
    }
    if (overrideLegacyPortEnvVar) {
      setEnv(DD_AGENT_PORT_LEGACY_ENV, "888");
    }

    if (overridePort) {
      System.setProperty(PREFIX + TRACE_AGENT_PORT, "123");
    }
    if (overrideLegacyPort) {
      System.setProperty(PREFIX + AGENT_PORT_LEGACY, "456");
    }

    Config config = newConfig();

    assertEquals(expectedPort, config.getAgentPort());
  }

  // FIXME: this seems to be a repeated test
  @Test
  void sysPropsOverrideProperties() {
    Properties properties = new Properties();
    properties.setProperty(SERVICE_NAME, "something else");
    properties.setProperty(TRACE_ENABLED, "false");
    properties.setProperty(WRITER_TYPE, "LoggingWriter");
    properties.setProperty(PRIORITIZATION_TYPE, "EnsureTrace");
    properties.setProperty(AGENT_HOST, "somehost");
    properties.setProperty(TRACE_AGENT_PORT, "123");
    properties.setProperty(AGENT_UNIX_DOMAIN_SOCKET, "somepath");
    properties.setProperty(PRIORITY_SAMPLING, "false");
    properties.setProperty(TRACE_RESOLVER_ENABLED, "false");
    properties.setProperty(SERVICE_MAPPING, "a:1");
    properties.setProperty(GLOBAL_TAGS, "b:2");
    properties.setProperty(SPAN_TAGS, "c:3");
    properties.setProperty(JMX_TAGS, "d:4");
    properties.setProperty(HEADER_TAGS, "e:five");
    properties.setProperty(BAGGAGE_MAPPING, "f:six,g");
    properties.setProperty(HTTP_SERVER_ERROR_STATUSES, "123-456,457,124-125,122");
    properties.setProperty(HTTP_CLIENT_ERROR_STATUSES, "111");
    properties.setProperty(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true");
    properties.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true");
    properties.setProperty(DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, "true");
    properties.setProperty(DB_CLIENT_HOST_SPLIT_BY_HOST, "true");
    properties.setProperty(PARTIAL_FLUSH_MIN_SPANS, "15");
    properties.setProperty(PROPAGATION_STYLE_EXTRACT, "B3 Datadog");
    properties.setProperty(PROPAGATION_STYLE_INJECT, "Datadog B3");
    properties.setProperty(JMX_FETCH_METRICS_CONFIGS, "/foo.yaml,/bar.yaml");
    properties.setProperty(JMX_FETCH_CHECK_PERIOD, "100");
    properties.setProperty(JMX_FETCH_REFRESH_BEANS_PERIOD, "200");
    properties.setProperty(JMX_FETCH_STATSD_HOST, "statsd host");
    properties.setProperty(JMX_FETCH_STATSD_PORT, "321");

    Config config = Config.get(properties);

    assertEquals("something else", config.getServiceName());
    assertFalse(config.isTraceEnabled());
    assertEquals("LoggingWriter", config.getWriterType());
    assertEquals("somehost", config.getAgentHost());
    assertEquals(123, config.getAgentPort());
    assertEquals("somepath", config.getAgentUnixDomainSocket());
    assertEquals("http://somehost:123", config.getAgentUrl());
    assertFalse(config.isPrioritySamplingEnabled());
    assertFalse(config.isTraceResolverEnabled());
    assertEquals(singletonMap("a", "1"), config.getServiceMapping());
    assertEquals(mapOf("b", "2", "c", "3"), config.getMergedSpanTags());
    assertEquals(
        mapOf(
            "b",
            "2",
            "d",
            "4",
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            config.getServiceName()),
        config.getMergedJmxTags());
    assertEquals(singletonMap("e", "five"), config.getRequestHeaderTags());
    assertEquals(mapOf("f", "six", "g", "g"), config.getBaggageMapping());
    assertEquals(toBitSet(122, 457), config.getHttpServerErrorStatuses());
    assertEquals(toBitSet(111, 111), config.getHttpClientErrorStatuses());
    assertTrue(config.isHttpClientSplitByDomain());
    assertTrue(config.isDbClientSplitByInstance());
    assertTrue(config.isDbClientSplitByInstanceTypeSuffix());
    assertTrue(config.isDbClientSplitByHost());
    assertEquals(emptySet(), config.getSplitByTags());
    assertEquals(15, config.getPartialFlushMinSpans());
    assertEquals(
        asList(PropagationStyle.B3, PropagationStyle.DATADOG),
        new ArrayList<>(config.getPropagationStylesToExtract()));
    assertEquals(
        asList(PropagationStyle.DATADOG, PropagationStyle.B3),
        new ArrayList<>(config.getPropagationStylesToInject()));
    assertEquals(
        asList(B3SINGLE, B3MULTI, DATADOG),
        new ArrayList<>(config.getTracePropagationStylesToExtract()));
    assertEquals(
        asList(DATADOG, B3SINGLE, B3MULTI),
        new ArrayList<>(config.getTracePropagationStylesToInject()));
    assertEquals(asList("/foo.yaml", "/bar.yaml"), config.getJmxFetchMetricsConfigs());
    assertEquals(Integer.valueOf(100), config.getJmxFetchCheckPeriod());
    assertEquals(Integer.valueOf(200), config.getJmxFetchRefreshBeansPeriod());
    assertEquals("statsd host", config.getJmxFetchStatsdHost());
    assertEquals(Integer.valueOf(321), config.getJmxFetchStatsdPort());
  }

  @Test
  void overrideNullProperties() {
    Config config = Config.get(null);

    assertEquals("unnamed-java-app", config.getServiceName());
    assertEquals("DDAgentWriter", config.getWriterType());
  }

  @Test
  void overrideEmptyProperties() {
    Properties properties = new Properties();

    Config config = Config.get(properties);

    assertEquals("unnamed-java-app", config.getServiceName());
    assertEquals("DDAgentWriter", config.getWriterType());
  }

  @Test
  void overrideNonEmptyProperties() {
    Properties properties = new Properties();
    properties.setProperty("foo", "bar");

    Config config = Config.get(properties);

    assertEquals("unnamed-java-app", config.getServiceName());
    assertEquals("DDAgentWriter", config.getWriterType());
  }

  @Test
  void capturedEnvPropsOverrideDefaultProps() throws Exception {
    useFixedEnv(singletonMap(SERVICE_NAME, "test service name"));

    Config config = newConfig();

    assertEquals("test service name", config.getServiceName());
  }

  @Test
  void specifyPropsOverrideCapturedEnvProps() {
    Properties properties = new Properties();
    properties.setProperty(SERVICE_NAME, "what actually wants");

    useFixedEnv(singletonMap(SERVICE_NAME, "something else"));

    Config config = Config.get(properties);

    assertEquals("what actually wants", config.getServiceName());
  }

  @Test
  void sysPropsOverrideCapturedEnvProps() throws Exception {
    System.setProperty(PREFIX + SERVICE_NAME, "what actually wants");

    useFixedEnv(singletonMap(SERVICE_NAME, "something else"));

    Config config = newConfig();

    assertEquals("what actually wants", config.getServiceName());
  }

  @Test
  void envVarsOverrideCapturedEnvProps() throws Exception {
    setEnv(DD_SERVICE_NAME_ENV, "what actually wants");

    useFixedEnv(singletonMap(SERVICE_NAME, "something else"));

    Config config = newConfig();

    assertEquals("what actually wants", config.getServiceName());
  }

  @ParameterizedTest(name = "[{index}] verify mapping configs on tracer for ''{0}''")
  @MethodSource("verifyMappingConfigsOnTracerArguments")
  void verifyMappingConfigsOnTracer(String mapString, Map<String, String> map) throws Exception {
    System.setProperty(PREFIX + HEADER_TAGS + ".legacy.parsing.enabled", "true");
    System.setProperty(PREFIX + SERVICE_MAPPING, mapString);
    System.setProperty(PREFIX + SPAN_TAGS, mapString);
    System.setProperty(PREFIX + HEADER_TAGS, mapString);
    System.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rqh1");
    System.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsh1");
    Properties props = new Properties();
    props.setProperty(HEADER_TAGS + ".legacy.parsing.enabled", "true");
    props.setProperty(SERVICE_MAPPING, mapString);
    props.setProperty(SPAN_TAGS, mapString);
    props.setProperty(HEADER_TAGS, mapString);
    props.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rqh1");
    props.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsh1");

    Config config = newConfig();
    Config propConfig = Config.get(props);

    assertEquals(map, config.getServiceMapping());
    assertEquals(map, spanTags(config));
    assertEquals(map, config.getRequestHeaderTags());
    assertEquals(emptyMap(), config.getResponseHeaderTags());
    assertEquals(map, propConfig.getServiceMapping());
    assertEquals(map, spanTags(propConfig));
    assertEquals(map, propConfig.getRequestHeaderTags());
    assertEquals(emptyMap(), propConfig.getResponseHeaderTags());
  }

  static Stream<Arguments> verifyMappingConfigsOnTracerArguments() {
    // spotless:off
    return Stream.of(
        arguments("a:1, a:2, a:3", mapOf("a", "3")),
        arguments("a:b,c:d,e:", mapOf("a", "b", "c", "d")),
        // space separated
        arguments("a:1  a:2  a:3", mapOf("a", "3")),
        arguments("a:b c:d e:", mapOf("a", "b", "c", "d")),
        // More different string variants:
        arguments("a:", mapOf()),
        arguments("a:a;", mapOf("a", "a;")),
        arguments("a:1, a:2, a:3", mapOf("a", "3")),
        arguments("a:1  a:2  a:3", mapOf("a", "3")),
        arguments("a:b,c:d,e:", mapOf("a", "b", "c", "d")),
        arguments("a:b c:d e:", mapOf("a", "b", "c", "d")),
        arguments("key 1!:va|ue_1,", mapOf("key 1!", "va|ue_1")),
        arguments("key 1!:va|ue_1 ", mapOf("key 1!", "va|ue_1")),
        arguments(" key1 :value1 ,\t key2:  value2", mapOf("key1", "value1", "key2", "value2")),
        arguments("a:b,c,d", mapOf()),
        arguments("a:b,c,d,k:v", mapOf()),
        arguments("key1 :value1  \t key2:  value2", mapOf("key1", "value1", "key2", "value2")),
        arguments("dyno:web.1 dynotype:web buildpackversion:dev appname:******", mapOf("dyno", "web.1", "dynotype", "web", "buildpackversion", "dev", "appname", "******")),
        arguments("is:val:id", mapOf("is", "val:id")),
        arguments("a:b,is:val:id,x:y", mapOf("a", "b", "is", "val:id", "x", "y")),
        arguments("a:b:c:d", mapOf("a", "b:c:d")),
        // Invalid strings:
        arguments("", mapOf()),
        arguments("1", mapOf()),
        arguments("a", mapOf()),
        arguments("a,1", mapOf()),
        arguments("!a", mapOf()),
        arguments("    ", mapOf()),
        arguments(",,,,", mapOf()),
        arguments(":,:,:,:,", mapOf()),
        arguments(": : : : ", mapOf()),
        arguments("::::", mapOf()));
    // spotless:on
  }

  @ParameterizedTest(name = "[{index}] verify mapping header tags on tracer for ''{0}''")
  @MethodSource("verifyMappingHeaderTagsOnTracerArguments")
  void verifyMappingHeaderTagsOnTracer(String mapString, Map<String, String> map) throws Exception {
    Map<String, String> requestHeaderTags = new HashMap<>(map);
    requestHeaderTags.put("rqh1", "http.request.headers.rqh1");
    System.setProperty(PREFIX + HEADER_TAGS, mapString);
    System.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rqh1");
    System.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsh1");
    Map<String, String> responseHeaderTags = new HashMap<>();
    map.forEach(
        (key, value) ->
            responseHeaderTags.put(
                key, value.replace("http.request.headers", "http.response.headers")));
    responseHeaderTags.put("rsh1", "http.response.headers.rsh1");
    Properties props = new Properties();
    props.setProperty(HEADER_TAGS, mapString);
    props.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rQh1");
    props.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsH1");

    Config config = newConfig();
    Config propConfig = Config.get(props);

    assertEquals(requestHeaderTags, config.getRequestHeaderTags());
    assertEquals(requestHeaderTags, propConfig.getRequestHeaderTags());
    assertEquals(responseHeaderTags, config.getResponseHeaderTags());
    assertEquals(responseHeaderTags, propConfig.getResponseHeaderTags());
  }

  static Stream<Arguments> verifyMappingHeaderTagsOnTracerArguments() {
    // spotless:off
    return Stream.of(
        arguments("a:one, a:two, a:three", mapOf("a", "three")),
        arguments("a:b,c:d,e:", mapOf("a", "b", "c", "d")),
        // space separated
        arguments("a:one  a:two  a:three", mapOf("a", "three")),
        arguments("a:b c:d e:", mapOf("a", "b", "c", "d")),
        // More different string variants:
        arguments("a:", mapOf()),
        arguments("a:a;", mapOf("a", "a;")),
        arguments("a:one, a:two, a:three", mapOf("a", "three")),
        arguments("a:one  a:two  a:three", mapOf("a", "three")),
        arguments("a:b,c:d,e:", mapOf("a", "b", "c", "d")),
        arguments("a:b c:d e:", mapOf("a", "b", "c", "d")),
        arguments("key=1!:va|ue_1,", mapOf("key=1!", "va|ue_1")),
        arguments("key=1!:va|ue_1 ", mapOf("key=1!", "va|ue_1")),
        arguments(" kEy1 :vaLue1 ,\t keY2:  valUe2", mapOf("key1", "vaLue1", "key2", "valUe2")),
        arguments("a:b,c,D", mapOf("a", "b", "c", "http.request.headers.c", "d", "http.request.headers.d")),
        arguments("a:b,C,d,k:v", mapOf("a", "b", "c", "http.request.headers.c", "d", "http.request.headers.d", "k", "v")),
        arguments("a b c:d ", mapOf("a", "http.request.headers.a", "b", "http.request.headers.b", "c", "d")),
        arguments("dyno:web.1 dynotype:web buildpackversion:dev appname:n*****", mapOf("dyno", "web.1", "dynotype", "web", "buildpackversion", "dev", "appname", "n*****")),
        arguments("A.1,B.1", mapOf("a.1", "http.request.headers.a_1", "b.1", "http.request.headers.b_1")),
        arguments("is:val:id", mapOf("is", "val:id")),
        arguments("a:b,is:val:id,x:y", mapOf("a", "b", "is", "val:id", "x", "y")),
        arguments("a:b:c:d", mapOf("a", "b:c:d")),
        // Invalid strings:
        arguments("", mapOf()),
        arguments("1", mapOf()),
        arguments("a:1", mapOf()),
        arguments("a,1", mapOf()),
        arguments("!a", mapOf()),
        arguments("    ", mapOf()),
        arguments(",,,,", mapOf()),
        arguments(":,:,:,:,", mapOf()),
        arguments(": : : : ", mapOf()),
        arguments("::::", mapOf()),
        arguments("kEy1 :value1  \t keY2:  value2", mapOf()));
    // spotless:on
  }

  // null expected means default value
  // spotless:off
  @TableTest({
    "scenario             | value               | expected",
    "single value         | 1                   | [1]",
    "values and range     | '3,13,400-403'      | [3, 13, 400, 401, 402, 403]",
    "values and range 2   | '2,10,13-15'        | [2, 10, 13, 14, 15]",
    "not a number         | a                   |",
    "empty                | ''                  |",
    "out of range         | 1000                |",
    "invalid range        | 100-200-300         |",
    "500                  | 500                 | [500]",
    "two values           | '100,999'           | [100, 999]",
    "two ranges           | '400-403,405-407'   | [400, 401, 402, 403, 405, 406, 407]",
    "with spaces          | ' 400 - 403 , 405 ' | [400, 401, 402, 403, 405]"
  })
  // spotless:on
  @MethodSource("verifyIntegerRangeConfigsOnTracerArguments")
  void verifyIntegerRangeConfigsOnTracer(String value, List<Integer> expected) throws Exception {
    System.setProperty(PREFIX + HTTP_SERVER_ERROR_STATUSES, value);
    System.setProperty(PREFIX + HTTP_CLIENT_ERROR_STATUSES, value);
    Properties props = new Properties();
    props.setProperty(HTTP_CLIENT_ERROR_STATUSES, value);
    props.setProperty(HTTP_SERVER_ERROR_STATUSES, value);

    Config config = newConfig();
    Config propConfig = Config.get(props);

    if (expected != null) {
      assertEquals(toBitSet(expected), config.getHttpServerErrorStatuses());
      assertEquals(toBitSet(expected), config.getHttpClientErrorStatuses());
      assertEquals(toBitSet(expected), propConfig.getHttpServerErrorStatuses());
      assertEquals(toBitSet(expected), propConfig.getHttpClientErrorStatuses());
    } else {
      assertEquals(DEFAULT_HTTP_SERVER_ERROR_STATUSES, config.getHttpServerErrorStatuses());
      assertEquals(DEFAULT_HTTP_CLIENT_ERROR_STATUSES, config.getHttpClientErrorStatuses());
      assertEquals(DEFAULT_HTTP_SERVER_ERROR_STATUSES, propConfig.getHttpServerErrorStatuses());
      assertEquals(DEFAULT_HTTP_CLIENT_ERROR_STATUSES, propConfig.getHttpClientErrorStatuses());
    }
  }

  static Stream<Arguments> verifyIntegerRangeConfigsOnTracerArguments() {
    // reversed range bounds
    return Stream.of(
        arguments("999-888", IntStream.rangeClosed(888, 999).boxed().collect(toList())));
  }

  @TableTest({
    "scenario | mapString",
    "null     |          ",
    "empty    | ''       "
  })
  void verifyNullValueMappingConfigsOnTracer(String mapString) throws Exception {
    setEnv(DD_SERVICE_MAPPING_ENV, mapString);
    setEnv(DD_SPAN_TAGS_ENV, mapString);
    setEnv(DD_HEADER_TAGS_ENV, mapString);

    Config config = newConfig();

    assertEquals(emptyMap(), config.getServiceMapping());
    assertEquals(emptyMap(), spanTags(config));
    assertEquals(emptyMap(), config.getRequestHeaderTags());
  }

  @Test
  void verifyEmptyValueListConfigsOnTracer() throws Exception {
    System.setProperty(PREFIX + JMX_FETCH_METRICS_CONFIGS, "");

    Config config = newConfig();

    assertEquals(emptyList(), config.getJmxFetchMetricsConfigs());
  }

  @Test
  void verifyHostnameNotAddedToRootSpanTagsByDefault() {
    Properties properties = new Properties();

    Config config = Config.get(properties);

    assertFalse(config.getLocalRootSpanTags().containsKey("_dd.hostname"));
  }

  @Test
  void verifyConfigurationToAddHostnameToRootSpanTags() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_REPORT_HOSTNAME, "true");

    Config config = Config.get(properties);

    assertTrue(config.getLocalRootSpanTags().containsKey("_dd.hostname"));
  }

  @Test
  void verifySchemaVersionIsAddedToLocalRootSpan() {
    Config config = Config.get();

    assertEquals(0, config.getLocalRootSpanTags().get("_dd.trace_span_attribute_schema"));
  }

  @Test
  void verifyFallbackToPropertiesFile() throws Exception {
    System.setProperty(PREFIX + CONFIGURATION_FILE, "src/test/resources/dd-java-tracer.properties");

    Config config = newConfig();

    assertEquals("src/test/resources/dd-java-tracer.properties", config.getConfigFileStatus());
    assertEquals("set-in-properties", config.getServiceName());
  }

  @Test
  void verifyFallbackToPropertiesFileHasLowerPriorityThanSystemProperty() throws Exception {
    System.setProperty(PREFIX + CONFIGURATION_FILE, "src/test/resources/dd-java-tracer.properties");
    System.setProperty(PREFIX + SERVICE_NAME, "set-in-system");

    Config config = newConfig();

    assertEquals("src/test/resources/dd-java-tracer.properties", config.getConfigFileStatus());
    assertEquals("set-in-system", config.getServiceName());
  }

  @Test
  void verifyFallbackToPropertiesFileHasLowerPriorityThanEnvVar() throws Exception {
    System.setProperty(PREFIX + CONFIGURATION_FILE, "src/test/resources/dd-java-tracer.properties");
    setEnv("DD_SERVICE_NAME", "set-in-env");

    Config config = newConfig();

    assertEquals("src/test/resources/dd-java-tracer.properties", config.getConfigFileStatus());
    assertEquals("set-in-env", config.getServiceName());
  }

  @Test
  void verifyFallbackToDdService() throws Exception {
    setEnv("DD_SERVICE", "service-name-from-dd-service-env-var");

    Config config = newConfig();

    assertEquals("service-name-from-dd-service-env-var", config.getServiceName());
  }

  @Test
  void verifyFallbackToPropertiesFileThatDoesNotExistDoesNotCrashApp() throws Exception {
    System.setProperty(PREFIX + CONFIGURATION_FILE, "src/test/resources/do-not-exist.properties");

    Config config = newConfig();

    assertEquals("unnamed-java-app", config.getServiceName());
  }

  @ParameterizedTest(name = "verify api key loaded from file: {0}")
  @MethodSource("verifyApiKeyLoadedFromFileArguments")
  void verifyApiKeyLoadedFromFile(String path, String expectedKey) throws Exception {
    setEnv(DD_API_KEY_ENV, "default-api-key");
    System.setProperty(PREFIX + API_KEY_FILE, path);

    Config config = newConfig();

    assertEquals(expectedKey, config.getApiKey());
  }

  static Stream<Arguments> verifyApiKeyLoadedFromFileArguments() {
    return Stream.of(
        arguments(resourceFile("apikey"), "test-api-key"),
        arguments("/path/that/doesnt/exist", "default-api-key"));
  }

  @Test
  void verifyApiKeyLoadedFromOldOptionName() throws Exception {
    setEnv(DD_PROFILING_API_KEY_OLD_ENV, "old-api-key");

    Config config = newConfig();

    assertEquals("old-api-key", config.getApiKey());
  }

  @ParameterizedTest(name = "verify api key loaded from file for old option name: {0}")
  @MethodSource("verifyApiKeyLoadedFromFileForOldOptionNameArguments")
  void verifyApiKeyLoadedFromFileForOldOptionName(String path, String expectedKey)
      throws Exception {
    setEnv(DD_PROFILING_API_KEY_OLD_ENV, "default-api-key");
    System.setProperty(PREFIX + PROFILING_API_KEY_FILE_OLD, path);

    Config config = newConfig();

    assertEquals(expectedKey, config.getApiKey());
  }

  static Stream<Arguments> verifyApiKeyLoadedFromFileForOldOptionNameArguments() {
    return Stream.of(
        arguments(resourceFile("apikey.old"), "test-api-key-old"),
        arguments("/path/that/doesnt/exist", "default-api-key"));
  }

  @Test
  void verifyApiKeyLoadedFromVeryOldOptionName() throws Exception {
    setEnv(DD_PROFILING_API_KEY_VERY_OLD_ENV, "very-old-api-key");

    Config config = newConfig();

    assertEquals("very-old-api-key", config.getApiKey());
  }

  @ParameterizedTest(name = "verify api key loaded from file for very old option name: {0}")
  @MethodSource("verifyApiKeyLoadedFromFileForVeryOldOptionNameArguments")
  void verifyApiKeyLoadedFromFileForVeryOldOptionName(String path, String expectedKey)
      throws Exception {
    setEnv(DD_PROFILING_API_KEY_VERY_OLD_ENV, "default-api-key");
    System.setProperty(PREFIX + PROFILING_API_KEY_FILE_VERY_OLD, path);

    Config config = newConfig();

    assertEquals(expectedKey, config.getApiKey());
  }

  static Stream<Arguments> verifyApiKeyLoadedFromFileForVeryOldOptionNameArguments() {
    return Stream.of(
        arguments(resourceFile("apikey.very-old"), "test-api-key-very-old"),
        arguments("/path/that/doesnt/exist", "default-api-key"));
  }

  @Test
  void verifyApiKeyLoadedFromNewOptionWhenBothNewAndOldAreSet() throws Exception {
    System.setProperty(PREFIX + API_KEY_FILE, resourceFile("apikey"));
    System.setProperty(PREFIX + PROFILING_API_KEY_FILE_OLD, resourceFile("apikey.old"));

    Config config = newConfig();

    assertEquals("test-api-key", config.getApiKey());
  }

  @Test
  void verifyApiKeyLoadedFromNewOptionWhenBothOldAndVeryOldAreSet() throws Exception {
    System.setProperty(PREFIX + PROFILING_API_KEY_FILE_OLD, resourceFile("apikey.old"));
    System.setProperty(PREFIX + PROFILING_API_KEY_FILE_VERY_OLD, resourceFile("apikey.very-old"));

    Config config = newConfig();

    assertEquals("test-api-key-old", config.getApiKey());
  }

  private static String resourceFile(String name) {
    return ConfigTest.class.getClassLoader().getResource(name).getFile();
  }

  /** Builds a map from alternating keys and values. */
  private static Map<String, String> mapOf(String... keysAndValues) {
    Map<String, String> map = new HashMap<>();
    for (int index = 0; index < keysAndValues.length; index += 2) {
      map.put(keysAndValues[index], keysAndValues[index + 1]);
    }
    return map;
  }

  /** Reads the private span tags field, which has no getter (only merged span tags do). */
  @SuppressWarnings("unchecked")
  private static Map<String, String> spanTags(Config config) throws ReflectiveOperationException {
    return (Map<String, String>) fieldValue(config, "spanTags");
  }

  @Test
  void verifyDdTagsOverridesGlobalTagsInProperties() {
    Properties prop = new Properties();
    prop.setProperty(TAGS, "a:1,env:us-west,version:42");
    prop.setProperty(GLOBAL_TAGS, "b:2");
    prop.setProperty(SPAN_TAGS, "c:3");
    prop.setProperty(JMX_TAGS, "d:4");
    prop.setProperty(HEADER_TAGS, "e:five");
    prop.setProperty(PROFILING_TAGS, "f:6");
    prop.setProperty(ENV, "eu-east");
    prop.setProperty(VERSION, "43");

    Config config = Config.get(prop);

    assertEquals(
        mapOf("a", "1", "b", "2", "c", "3", ENV, "eu-east", VERSION, "43"),
        config.getMergedSpanTags());
    assertEquals(
        mapOf(
            "a",
            "1",
            "b",
            "2",
            "d",
            "4",
            ENV,
            "eu-east",
            VERSION,
            "43",
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            config.getServiceName()),
        config.getMergedJmxTags());
    assertEquals(mapOf("e", "five"), config.getRequestHeaderTags());

    assertEquals(
        mapOf(
            "a",
            "1",
            "b",
            "2",
            "f",
            "6",
            ENV,
            "eu-east",
            VERSION,
            "43",
            HOST_TAG,
            config.getHostName(),
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            RUNTIME_VERSION_TAG,
            config.getRuntimeVersion(),
            SERVICE_TAG,
            config.getServiceName(),
            LANGUAGE_TAG_KEY,
            LANGUAGE_TAG_VALUE),
        config.getMergedProfilingTags());
  }

  @Test
  void verifyDdTagsOverridesGlobalTagsInSystemProperties() throws Exception {
    System.setProperty(PREFIX + TAGS, "a:1");
    System.setProperty(PREFIX + GLOBAL_TAGS, "b:2");
    System.setProperty(PREFIX + SPAN_TAGS, "c:3");
    System.setProperty(PREFIX + JMX_TAGS, "d:4");
    System.setProperty(PREFIX + HEADER_TAGS, "e:five");
    System.setProperty(PREFIX + PROFILING_TAGS, "f:6");

    Config config = newConfig();

    assertEquals(mapOf("a", "1", "b", "2", "c", "3"), config.getMergedSpanTags());
    assertEquals(
        mapOf(
            "a",
            "1",
            "b",
            "2",
            "d",
            "4",
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            config.getServiceName()),
        config.getMergedJmxTags());
    assertEquals(mapOf("e", "five"), config.getRequestHeaderTags());

    assertEquals(
        mapOf(
            "a",
            "1",
            "b",
            "2",
            "f",
            "6",
            HOST_TAG,
            config.getHostName(),
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            RUNTIME_VERSION_TAG,
            config.getRuntimeVersion(),
            SERVICE_TAG,
            config.getServiceName(),
            LANGUAGE_TAG_KEY,
            LANGUAGE_TAG_VALUE),
        config.getMergedProfilingTags());
  }

  @Test
  void verifyDdTagsMergesWithGlobalTagsInEnvVariables() throws Exception {
    setEnv(DD_TAGS_ENV, "a:1:2");
    setEnv(DD_GLOBAL_TAGS_ENV, "b:2");
    setEnv(DD_SPAN_TAGS_ENV, "c:3");
    setEnv(DD_JMX_TAGS_ENV, "d:4");
    setEnv(DD_HEADER_TAGS_ENV, "e:five");
    setEnv(DD_PROFILING_TAGS_ENV, "f:6");

    Config config = newConfig();

    assertEquals(mapOf("a", "1:2", "b", "2", "c", "3"), config.getMergedSpanTags());
    assertEquals(
        mapOf(
            "a",
            "1:2",
            "b",
            "2",
            "d",
            "4",
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            config.getServiceName()),
        config.getMergedJmxTags());
    assertEquals(mapOf("e", "five"), config.getRequestHeaderTags());

    assertEquals(
        mapOf(
            "a",
            "1:2",
            "b",
            "2",
            "f",
            "6",
            HOST_TAG,
            config.getHostName(),
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            RUNTIME_VERSION_TAG,
            config.getRuntimeVersion(),
            SERVICE_TAG,
            config.getServiceName(),
            LANGUAGE_TAG_KEY,
            LANGUAGE_TAG_VALUE),
        config.getMergedProfilingTags());
  }

  @Test
  void toStringWorksWhenPasswordsAreEmpty() throws Exception {
    Config config = newConfig();

    String configString = config.toString();
    assertTrue(configString.contains("apiKey=null"));
    assertTrue(configString.contains("profilingProxyPassword=null"));
  }

  @Test
  void sensitiveInformationRemovedForToStringDebugLog() throws Exception {
    setEnv(DD_API_KEY_ENV, "test-secret-api-key");
    setEnv(DD_PROFILING_PROXY_PASSWORD_ENV, "test-secret-proxy-password");

    Config config = newConfig();

    String configString = config.toString();
    assertTrue(configString.contains("apiKey=****"));
    assertFalse(configString.contains("test-secret-api-key"));
    assertTrue(configString.contains("profilingProxyPassword=****"));
    assertFalse(configString.contains("test-secret-proxy-password"));
    assertEquals("test-secret-api-key", config.getApiKey());
    assertEquals("test-secret-proxy-password", config.getProfilingProxyPassword());
  }

  @Test
  void customDatadogSiteWithAgentlessProfiling() {
    Properties prop = new Properties();
    prop.setProperty(SITE, "some.new.site");
    prop.setProperty(PROFILING_AGENTLESS, "true");

    Config config = Config.get(prop);

    assertEquals(
        "https://intake.profile.some.new.site/api/v2/profile", config.getFinalProfilingUrl());
  }

  @Test
  void customDatadogSiteWithoutAgentlessProfiling() {
    Properties prop = new Properties();
    prop.setProperty(SITE, "some.new.site");

    Config config = Config.get(prop);

    assertEquals(
        "http://" + config.getAgentHost() + ":" + config.getAgentPort() + "/profiling/v1/input",
        config.getFinalProfilingUrl());
  }

  @Test
  void presenceOfApiKeyDoesNotLeadToAgentlessProfiling() {
    Properties prop = new Properties();
    prop.setProperty(API_KEY, "some.api.key");

    Config config = Config.get(prop);

    assertEquals(
        "http://" + config.getAgentHost() + ":" + config.getAgentPort() + "/profiling/v1/input",
        config.getFinalProfilingUrl());
  }

  @Test
  void customProfilingUrlOverride() {
    Properties prop = new Properties();
    prop.setProperty(SITE, "some.new.site");
    prop.setProperty(PROFILING_URL, "https://some.new.url/goes/here");

    Config config = Config.get(prop);

    assertEquals("https://some.new.url/goes/here", config.getFinalProfilingUrl());
  }

  @Test
  void ipv6ProfilingUrl() {
    String configuredUrl = "http://[2600:1f14:1cfc:5f07::38d4]:8126";
    Properties props = new Properties();
    props.setProperty(TRACE_AGENT_URL, configuredUrl);

    Config config = Config.get(props);

    assertEquals(configuredUrl + "/profiling/v1/input", config.getFinalProfilingUrl());
  }

  @Test
  void udsProfilingUrl() {
    String configuredUrl = "unix:///path/to/socket";
    Properties props = new Properties();
    props.setProperty(TRACE_AGENT_URL, configuredUrl);

    Config config = Config.get(props);

    assertEquals(
        "http://" + config.getAgentHost() + ":" + config.getAgentPort() + "/profiling/v1/input",
        config.getFinalProfilingUrl());
  }

  @Test
  void fallbackToDdTags() throws Exception {
    setEnv(DD_TAGS_ENV, "a:1,b:2,c:3");

    Config config = newConfig();

    assertEquals(mapOf("a", "1", "c", "3", "b", "2"), config.getMergedSpanTags());
  }

  @Test
  void explicitDdEnvAndDdVersionOverwriteDdTags() throws Exception {
    setEnv(DD_TAGS_ENV, "env:production   ,    version:3.2.1");
    setEnv(DD_ENV_ENV, "test_env");
    setEnv(DD_VERSION_ENV, "1.2.3");

    Config config = newConfig();

    assertEquals(mapOf("env", "test_env", "version", "1.2.3"), config.getMergedSpanTags());
    assertEquals("test_env", config.getWellKnownTags().getEnv().toString());
    assertEquals("1.2.3", config.getWellKnownTags().getVersion().toString());
  }

  @Test
  void explicitDdEnvAndDdVersionOverwritesDdTraceGlobalTags() throws Exception {
    setEnv(DD_VERSION_ENV, "1.2.3");
    setEnv(DD_ENV_ENV, "production-us");
    System.setProperty(PREFIX + GLOBAL_TAGS, "env:us-barista-test,other_tag:test,version:3.2.1");

    Config config = newConfig();

    assertEquals(
        mapOf("version", "1.2.3", "env", "production-us", "other_tag", "test"),
        config.getMergedSpanTags());
    assertEquals("production-us", config.getWellKnownTags().getEnv().toString());
    assertEquals("1.2.3", config.getWellKnownTags().getVersion().toString());
  }

  @Test
  void mergeEnvFromDdTraceGlobalTagsAndDdVersion() throws Exception {
    setEnv(DD_VERSION_ENV, "1.2.3");
    System.setProperty(PREFIX + GLOBAL_TAGS, "env:us-barista-test,other_tag:test,version:3.2.1");

    Config config = newConfig();

    assertEquals(
        mapOf("version", "1.2.3", "env", "us-barista-test", "other_tag", "test"),
        config.getMergedSpanTags());
  }

  @Test
  void mergeVersionFromDdTraceGlobalTagsAndDdEnv() throws Exception {
    setEnv(DD_ENV_ENV, "us-barista-test");
    System.setProperty(PREFIX + GLOBAL_TAGS, "other_tag:test,version:3.2.1");

    Config config = newConfig();

    assertEquals(
        mapOf("version", "3.2.1", "env", "us-barista-test", "other_tag", "test"),
        config.getMergedSpanTags());
  }

  @Test
  void mergeVersionFromDdTraceGlobalTagsAndDdServiceAndDdEnv() throws Exception {
    setEnv("DD_SERVICE", "dd-service-env-var");
    setEnv(DD_ENV_ENV, "us-barista-test");
    System.setProperty(
        PREFIX + GLOBAL_TAGS, "other_tag:test,version:3.2.1,service.version:my-svc-vers");

    Config config = newConfig();

    assertEquals("dd-service-env-var", config.getServiceName());
    assertEquals(
        mapOf(
            "version",
            "3.2.1",
            "service.version",
            "my-svc-vers",
            "env",
            "us-barista-test",
            "other_tag",
            "test"),
        config.getMergedSpanTags());
    assertEquals(
        mapOf(
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            "dd-service-env-var",
            "version",
            "3.2.1",
            "service.version",
            "my-svc-vers",
            "env",
            "us-barista-test",
            "other_tag",
            "test"),
        config.getMergedJmxTags());
  }

  @Test
  void mergeEnvFromDdTraceGlobalTagsAndDdServiceAndDdVersion() throws Exception {
    setEnv("DD_SERVICE", "dd-service-env-var");
    setEnv(DD_VERSION_ENV, "3.2.1");
    System.setProperty(
        PREFIX + GLOBAL_TAGS, "other_tag:test,env:us-barista-test,service.version:my-svc-vers");

    Config config = newConfig();

    assertEquals("dd-service-env-var", config.getServiceName());
    assertEquals(
        mapOf(
            "version",
            "3.2.1",
            "service.version",
            "my-svc-vers",
            "env",
            "us-barista-test",
            "other_tag",
            "test"),
        config.getMergedSpanTags());
    assertEquals(
        mapOf(
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            "dd-service-env-var",
            "version",
            "3.2.1",
            "service.version",
            "my-svc-vers",
            "env",
            "us-barista-test",
            "other_tag",
            "test"),
        config.getMergedJmxTags());
  }

  @Test
  void setOfDdTraceGlobalTagsEnvExclusivelyByJavaPropertiesAndWithoutDdEnv() throws Exception {
    System.setProperty(PREFIX + GLOBAL_TAGS, "env:production");

    Config config = newConfig();

    // check that env wasn't set:
    assertNull(EnvironmentVariables.get(DD_ENV_ENV));
    assertNull(EnvironmentVariables.get(DD_VERSION_ENV));
    // actual guard:
    assertEquals(mapOf("env", "production"), config.getMergedSpanTags());
  }

  @Test
  void setOfDdTraceGlobalTagsVersionExclusivelyByJavaProperties() throws Exception {
    System.setProperty(PREFIX + GLOBAL_TAGS, "version:42");

    Config config = newConfig();

    // check that env wasn't set:
    assertNull(EnvironmentVariables.get(DD_ENV_ENV));
    assertNull(EnvironmentVariables.get(DD_VERSION_ENV));
    // actual guard:
    assertEquals(mapOf(VERSION, "42"), config.getMergedSpanTags());
  }

  @Test
  void setOfVersionExclusivelyByDdVersionAndWithoutDdEnv() throws Exception {
    setEnv(DD_VERSION_ENV, "3.2.1");

    Config config = newConfig();

    assertNull(EnvironmentVariables.get(DD_ENV_ENV));
    assertNull(config.getMergedSpanTags().get("env"));
    assertEquals(mapOf(VERSION, "3.2.1"), config.getMergedSpanTags());
  }

  // service name precedence checks
  @Test
  void defaultServiceNameExist() {
    assertEquals(DEFAULT_SERVICE_NAME, Config.get().getServiceName());
  }

  @Test
  void defaultServiceNameIsNotAffectedByTagsNorEnvVariables() throws Exception {
    System.setProperty(
        PREFIX + GLOBAL_TAGS,
        "service:service-tag-in-dd-trace-global-tags-java-property,service.version:my-svc-vers");

    Config config = newConfig();

    assertEquals(DEFAULT_SERVICE_NAME, config.getServiceName());
    assertEquals(
        mapOf(
            "service", "service-tag-in-dd-trace-global-tags-java-property",
            "service.version", "my-svc-vers"),
        config.getMergedSpanTags());
    assertEquals(
        mapOf(
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            config.getServiceName(),
            "service.version",
            "my-svc-vers"),
        config.getMergedJmxTags());
  }

  @Test
  void serviceNamePrioritizesValuesFromDdServiceOverTags() throws Exception {
    System.setProperty(PREFIX + TAGS, "service:service-name-from-tags");
    System.setProperty(PREFIX + SERVICE, "service-name-from-dd-service");

    Config config = newConfig();

    assertEquals("service-name-from-dd-service", config.getServiceName());
    assertFalse(config.getMergedSpanTags().containsKey("service"));
  }

  @Test
  void ddServicePrecedenceOverDdServiceNameJavaPropertyIsSetDdServiceOverwritesDdService()
      throws Exception {
    setEnv(DD_SERVICE_NAME_ENV, "dd-service-name-env-var");
    System.setProperty(PREFIX + SERVICE_NAME, "dd-service-name-java-prop");
    setEnv("DD_SERVICE", "dd-service-env-var");
    System.setProperty(PREFIX + SERVICE, "dd-service-java-prop");
    System.setProperty(
        PREFIX + GLOBAL_TAGS,
        "service:service-tag-in-dd-trace-global-tags-java-property,service.version:my-svc-vers");

    Config config = newConfig();

    assertEquals("dd-service-java-prop", config.getServiceName());
    assertEquals(mapOf("service.version", "my-svc-vers"), config.getMergedSpanTags());
    assertEquals(
        mapOf(
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            config.getServiceName(),
            "service.version",
            "my-svc-vers"),
        config.getMergedJmxTags());
  }

  @Test
  void ddServicePrecedenceOverDdServiceNameEnvironmentVarIsSet() throws Exception {
    setEnv(DD_SERVICE_NAME_ENV, "dd-service-name-env-var");
    setEnv("DD_SERVICE", "dd-service-env-var");
    System.setProperty(
        PREFIX + GLOBAL_TAGS,
        "service:service-tag-in-dd-trace-global-tags-java-property,service.version:my-svc-vers");

    Config config = newConfig();

    assertEquals("dd-service-env-var", config.getServiceName());
    assertEquals(mapOf("service.version", "my-svc-vers"), config.getMergedSpanTags());
    assertEquals(
        mapOf(
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            config.getServiceName(),
            "service.version",
            "my-svc-vers"),
        config.getMergedJmxTags());
  }

  @Test
  void ddServiceOverwritesDdService() throws Exception {
    setEnv("DD_SERVICE", "dd-service-env-var");
    System.setProperty(PREFIX + SERVICE, "dd-service-java-prop");
    System.setProperty(
        PREFIX + GLOBAL_TAGS,
        "service:service-tag-in-dd-trace-global-tags-java-property,service.version:my-svc-vers");

    Config config = newConfig();

    assertEquals("dd-service-java-prop", config.getServiceName());
    assertEquals(mapOf("service.version", "my-svc-vers"), config.getMergedSpanTags());
    assertEquals(
        mapOf(
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            config.getServiceName(),
            "service.version",
            "my-svc-vers"),
        config.getMergedJmxTags());
  }

  @Test
  void setServicenameByDdService() throws Exception {
    setEnv("DD_SERVICE", "dd-service-env-var");
    System.setProperty(
        PREFIX + GLOBAL_TAGS,
        "service:service-tag-in-dd-trace-global-tags-java-property,service.version:my-svc-vers");
    setEnv(DD_GLOBAL_TAGS_ENV, "service:service-tag-in-env-var,service.version:my-svc-vers");

    Config config = newConfig();

    assertEquals("dd-service-env-var", config.getServiceName());
    assertEquals(mapOf("service.version", "my-svc-vers"), config.getMergedSpanTags());
    assertEquals(
        mapOf(
            RUNTIME_ID_TAG,
            config.getRuntimeId(),
            SERVICE_TAG,
            config.getServiceName(),
            "service.version",
            "my-svc-vers"),
        config.getMergedJmxTags());
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("explicitServiceNameIsNotOverriddenByCapturedEnvironmentArguments")
  void explicitServiceNameIsNotOverriddenByCapturedEnvironment(
      String serviceProperty, String serviceName) throws Exception {
    System.setProperty(PREFIX + serviceProperty, serviceName);

    Config config = newConfig();

    assertEquals(serviceName, config.getServiceName());
    assertTrue(config.isServiceNameSetByUser());
  }

  static Stream<Arguments> explicitServiceNameIsNotOverriddenByCapturedEnvironmentArguments() {
    return Stream.of(
        arguments(SERVICE, DEFAULT_SERVICE_NAME),
        arguments(SERVICE_NAME, DEFAULT_SERVICE_NAME),
        arguments(SERVICE, "my-service"),
        arguments(SERVICE_NAME, "my-service"));
  }

  @Test
  void verifyBehaviorOfFeaturesUnderDdTraceExperimentalFeaturesEnabled() throws Exception {
    setEnv("DD_TRACE_EXPERIMENTAL_FEATURES_ENABLED", "DD_LOGS_INJECTION, DD_TAGS");
    setEnv("DD_TAGS", "env:test,aKey:aVal bKey:bVal cKey:");

    Config config = newConfig();

    assertEquals(
        new HashSet<>(asList("DD_LOGS_INJECTION", "DD_TAGS")),
        config.getExperimentalFeaturesEnabled());

    // verify expected behavior enabled under feature flag
    assertFalse(config.isLogsInjectionEnabled());
    assertEquals(mapOf("env", "test", "aKey", "aVal bKey:bVal cKey:"), config.getGlobalTags());
  }

  @Test
  void verifyBehaviorOfBreakingChangeConfigsWhenNotUnderDdTraceExperimentalFeaturesEnabled()
      throws Exception {
    setEnv("DD_TAGS", "env:test,aKey:aVal bKey:bVal cKey:");

    Config config = newConfig();

    assertEquals(emptySet(), config.getExperimentalFeaturesEnabled());

    // verify expected behavior when not enabled under feature flag
    assertTrue(config.isLogsInjectionEnabled());
    assertEquals(mapOf("env", "test", "aKey", "aVal", "bKey", "bVal"), config.getGlobalTags());
  }

  @Test
  void verifyBehaviorOfDdTraceExperimentalFeatureEnabledWhenValueIsAll() throws Exception {
    setEnv("DD_TRACE_EXPERIMENTAL_FEATURES_ENABLED", "all");

    Config config = newConfig();

    assertEquals(
        new HashSet<>(
            asList(
                "DD_TAGS",
                "DD_LOGS_INJECTION",
                "DD_EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED",
                "DD_TRACE_STATS_ADDITIONAL_TAGS")),
        config.getExperimentalFeaturesEnabled());
  }

  @TableTest({
    "scenario                                             | host      | socket    | port | legacyPort | propertyHost | propertySocket | propertyPort | configuredUsingDefault | childConfiguredUsingDefault",
    "'nothing configured'                                 |           |           |      |            |              |                |              | true                   | true                       ",
    "'host=example'                                       | 'example' |           |      |            |              |                |              | false                  | false                      ",
    "'socket=example'                                     |           | 'example' |      |            |              |                |              | false                  | false                      ",
    "'port=1'                                             |           |           | '1'  |            |              |                |              | false                  | false                      ",
    "'legacyPort=1'                                       |           |           |      | '1'        |              |                |              | false                  | false                      ",
    "'host=example, socket=example'                       | 'example' | 'example' |      |            |              |                |              | false                  | false                      ",
    "'propertyHost=example'                               |           |           |      |            | 'example'    |                |              | true                   | false                      ",
    "'propertySocket=example'                             |           |           |      |            |              | 'example'      |              | true                   | false                      ",
    "'propertyPort=1'                                     |           |           |      |            |              |                | '1'          | true                   | false                      ",
    "'host=example, socket=example, propertyHost=example' | 'example' | 'example' |      |            | 'example'    |                |              | false                  | false                      "
  })
  @MethodSource("detectIfAgentIsConfiguredUsingDefaultValuesArguments")
  void detectIfAgentIsConfiguredUsingDefaultValues(
      String host,
      String socket,
      String port,
      String legacyPort,
      String propertyHost,
      String propertySocket,
      String propertyPort,
      boolean configuredUsingDefault,
      boolean childConfiguredUsingDefault)
      throws Exception {
    if (host != null) {
      System.setProperty(PREFIX + AGENT_HOST, host);
    }
    if (socket != null) {
      System.setProperty(PREFIX + AGENT_UNIX_DOMAIN_SOCKET, socket);
    }
    if (port != null) {
      System.setProperty(PREFIX + TRACE_AGENT_PORT, port);
    }
    if (legacyPort != null) {
      System.setProperty(PREFIX + AGENT_PORT_LEGACY, legacyPort);
    }

    Config config = newConfig();

    assertEquals(configuredUsingDefault, config.isAgentConfiguredUsingDefault());

    Properties properties = new Properties();
    if (propertyHost != null) {
      properties.setProperty(AGENT_HOST, propertyHost);
    }
    if (propertySocket != null) {
      properties.setProperty(AGENT_UNIX_DOMAIN_SOCKET, propertySocket);
    }
    if (propertyPort != null) {
      properties.setProperty(TRACE_AGENT_PORT, propertyPort);
    }

    Config childConfig = newConfig(withPropertiesOverride(properties));

    assertEquals(childConfiguredUsingDefault, childConfig.isAgentConfiguredUsingDefault());
  }

  static Stream<Arguments> detectIfAgentIsConfiguredUsingDefaultValuesArguments() {
    // explicitly configured with the default host
    return Stream.of(
        arguments(
            named("host=default agent host", DEFAULT_AGENT_HOST),
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false));
  }

  @ParameterizedTest(name = "{0} as {1}")
  @MethodSource("valueOfPositiveTestArguments")
  void valueOfPositiveTest(String value, Class<?> tClass, Object expected) throws Exception {
    assertEquals(expected, configConverterValueOf(value, tClass));
  }

  static Stream<Arguments> valueOfPositiveTestArguments() {
    return Stream.of(
        arguments("true", type(Boolean.class), true),
        arguments("trUe", type(Boolean.class), true),
        arguments("false", type(Boolean.class), false),
        arguments("False", type(Boolean.class), false),
        arguments("1", type(Boolean.class), true),
        arguments("0", type(Boolean.class), false),
        arguments("42.42", type(Float.class), 42.42f),
        arguments("42.42", type(Double.class), 42.42),
        arguments("44", type(Integer.class), 44),
        arguments("45", type(Long.class), 45L),
        arguments("46", type(Short.class), (short) 46));
  }

  @TableTest({
    "scenario        | value   ",
    "'null value'    |         ",
    "'empty value'   | ''      ",
    "'blank value'   | '      '",
    "'numeric value' | '1'     "
  })
  void valueOfNegativeTestWhenTClassIsNull(String value) {
    NullPointerException exception =
        assertThrows(NullPointerException.class, () -> configConverterValueOf(value, null));
    assertEquals("tClass is marked non-null but is null", exception.getMessage());
  }

  @ParameterizedTest(name = "''{0}''")
  @ValueSource(
      strings = {
        "42.42",
        "tru",
        "truee",
        "true ",
        " true",
        " true ",
        "   true  ",
        "notABool",
        "invalid",
        "yes",
        "no",
        "42"
      })
  void valueOfNegativeTestForInvalidBooleanValues(String value) {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> configConverterValueOf(value, Boolean.class));
    assertTrue(exception.getMessage().contains("Invalid boolean value"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("valueOfNegativeTestArguments")
  void valueOfNegativeTest(String value, Class<?> tClass) {
    assertThrows(NumberFormatException.class, () -> configConverterValueOf(value, tClass));
  }

  static Stream<Arguments> valueOfNegativeTestArguments() {
    return Stream.of(
        arguments("42.42", type(Number.class)),
        arguments("42.42", type(Byte.class)),
        arguments("42.42", type(Character.class)),
        arguments("42.42", type(Short.class)),
        arguments("42.42", type(Integer.class)),
        arguments("42.42", type(Long.class)),
        arguments("42.42", type(Object.class)),
        arguments("42.42", type(Object[].class)),
        arguments("42.42", type(boolean[].class)),
        arguments("42.42", type(boolean.class)),
        arguments("42.42", type(byte.class)),
        arguments("42.42", type(byte.class)),
        arguments("42.42", type(char.class)),
        arguments("42.42", type(short.class)),
        arguments("42.42", type(int.class)),
        arguments("42.42", type(long.class)),
        arguments("42.42", type(double.class)),
        arguments("42.42", type(float.class)),
        // will wrapped in NumberFormatException anyway
        arguments("42.42", type(ClassThrowsExceptionForValueOfMethod.class)));
  }

  @Test
  void revertToRandomWithInvalidIdGenerationStrategy() {
    Properties prop = new Properties();
    prop.setProperty(ID_GENERATION_STRATEGY, "LOL");

    Config config = Config.get(prop);

    assertTrue(config.getIdGenerationStrategy().getClass().getName().endsWith("$Random"));
  }

  @Test
  void ddRuntimeMetricsEnabledFalseDisablesAllMetrics() {
    setEnv(DD_RUNTIME_METRICS_ENABLED_ENV, "false");
    Properties prop = new Properties();
    prop.setProperty(JMX_FETCH_ENABLED, "true");
    prop.setProperty(HEALTH_METRICS_ENABLED, "true");
    prop.setProperty(PERF_METRICS_ENABLED, "true");

    Config config = Config.get(prop);

    assertFalse(config.isJmxFetchEnabled());
    assertFalse(config.isHealthMetricsEnabled());
    assertFalse(config.isPerfMetricsEnabled());
  }

  @TableTest({
    "scenario                              | configuredUrl                     | expectedUrl                       | expectedHost | expectedPort | expectedUnixDomainSocket",
    "'url not set'                         |                                   | 'http://localhost:8126'           | 'localhost'  | 8126         |                         ",
    "'url=empty'                           | ''                                | 'http://localhost:8126'           | 'localhost'  | 8126         |                         ",
    "'url=http://localhost:1234'           | 'http://localhost:1234'           | 'http://localhost:1234'           | 'localhost'  | 1234         |                         ",
    "'url=http://somehost'                 | 'http://somehost'                 | 'http://somehost:8126'            | 'somehost'   | 8126         |                         ",
    "'url=http://somehost:80'              | 'http://somehost:80'              | 'http://somehost:80'              | 'somehost'   | 80           |                         ",
    "'url=https://somehost:8143'           | 'https://somehost:8143'           | 'https://somehost:8143'           | 'somehost'   | 8143         |                         ",
    "'url=unix:///another/socket/path'     | 'unix:///another/socket/path'     | 'unix:///another/socket/path'     | 'localhost'  | 8126         | '/another/socket/path'  ",
    "'url=unix:///another%2Fsocket%2Fpath' | 'unix:///another%2Fsocket%2Fpath' | 'unix:///another%2Fsocket%2Fpath' | 'localhost'  | 8126         | '/another/socket/path'  ",
    "'url=http:'                           | 'http:'                           | 'http://localhost:8126'           | 'localhost'  | 8126         |                         ",
    "'url=unix:'                           | 'unix:'                           | 'http://localhost:8126'           | 'localhost'  | 8126         |                         ",
    "'url=1234'                            | '1234'                            | 'http://localhost:8126'           | 'localhost'  | 8126         |                         ",
    "'url=:1234'                           | ':1234'                           | 'http://localhost:8126'           | 'localhost'  | 8126         |                         "
  })
  void traceAgentUrlOverridesDefaultHostAndPortOrUnixDomain(
      String configuredUrl,
      String expectedUrl,
      String expectedHost,
      int expectedPort,
      String expectedUnixDomainSocket)
      throws Exception {
    if (configuredUrl != null) {
      System.setProperty(PREFIX + TRACE_AGENT_URL, configuredUrl);
    } else {
      System.clearProperty(PREFIX + TRACE_AGENT_URL);
    }

    Config config = newConfig();

    assertEquals(expectedUrl, config.getAgentUrl());
    assertEquals(expectedHost, config.getAgentHost());
    assertEquals(expectedPort, config.getAgentPort());
    assertEquals(expectedUnixDomainSocket, config.getAgentUnixDomainSocket());
  }

  @TableTest({
    "scenario                              | configuredUrl                     | expectedUrl                       | expectedHost | expectedPort | expectedUnixDomainSocket",
    "'url not set'                         |                                   | 'http://test-host:8888'           | 'test-host'  | 8888         | '/path/to/socket'       ",
    "'url=empty'                           | ''                                | 'http://test-host:8888'           | 'test-host'  | 8888         | '/path/to/socket'       ",
    "'url=http://localhost:1234'           | 'http://localhost:1234'           | 'http://localhost:1234'           | 'localhost'  | 1234         | '/path/to/socket'       ",
    "'url=http://somehost'                 | 'http://somehost'                 | 'http://somehost:8888'            | 'somehost'   | 8888         | '/path/to/socket'       ",
    "'url=http://somehost:80'              | 'http://somehost:80'              | 'http://somehost:80'              | 'somehost'   | 80           | '/path/to/socket'       ",
    "'url=https://somehost:8143'           | 'https://somehost:8143'           | 'https://somehost:8143'           | 'somehost'   | 8143         | '/path/to/socket'       ",
    "'url=unix:///another/socket/path'     | 'unix:///another/socket/path'     | 'unix:///another/socket/path'     | 'localhost'  | 8126         | '/another/socket/path'  ",
    "'url=unix:///another%2Fsocket%2Fpath' | 'unix:///another%2Fsocket%2Fpath' | 'unix:///another%2Fsocket%2Fpath' | 'localhost'  | 8126         | '/another/socket/path'  ",
    "'url=http:'                           | 'http:'                           | 'http://test-host:8888'           | 'test-host'  | 8888         | '/path/to/socket'       ",
    "'url=unix:'                           | 'unix:'                           | 'http://test-host:8888'           | 'test-host'  | 8888         | '/path/to/socket'       ",
    "'url=1234'                            | '1234'                            | 'http://test-host:8888'           | 'test-host'  | 8888         | '/path/to/socket'       ",
    "'url=:1234'                           | ':1234'                           | 'http://test-host:8888'           | 'test-host'  | 8888         | '/path/to/socket'       "
  })
  void traceAgentUrlOverridesConfiguredHostAndPortOrUnixDomain(
      String configuredUrl,
      String expectedUrl,
      String expectedHost,
      int expectedPort,
      String expectedUnixDomainSocket)
      throws Exception {
    System.setProperty(PREFIX + AGENT_HOST, "test-host");
    System.setProperty(PREFIX + TRACE_AGENT_PORT, "8888");
    System.setProperty(PREFIX + AGENT_UNIX_DOMAIN_SOCKET, "/path/to/socket");
    if (configuredUrl != null) {
      System.setProperty(PREFIX + TRACE_AGENT_URL, configuredUrl);
    } else {
      System.clearProperty(PREFIX + TRACE_AGENT_URL);
    }

    Config config = newConfig();

    assertEquals(expectedUrl, config.getAgentUrl());
    assertEquals(expectedHost, config.getAgentHost());
    assertEquals(expectedPort, config.getAgentPort());
    assertEquals(expectedUnixDomainSocket, config.getAgentUnixDomainSocket());
  }

  @Test
  void testGetIgnoredResourceNames() throws Exception {
    System.setProperty(
        PREFIX + TRACER_METRICS_IGNORED_RESOURCES, "GET /healthcheck,SELECT foo from bar");

    Config config = newConfig();

    assertEquals(
        new HashSet<>(asList("GET /healthcheck", "SELECT foo from bar")),
        config.getMetricsIgnoredResources());
  }

  @TableTest({
    "scenario                    | sys        | env        | expected        ",
    "'sys=null env=null'         |            |            | ENABLED_INACTIVE",
    "'sys=null env=empty'        |            | ''         | ENABLED_INACTIVE",
    "'sys=null env=inactive'     |            | 'inactive' | ENABLED_INACTIVE",
    "'sys=null env=false'        |            | 'false'    | FULLY_DISABLED  ",
    "'sys=null env=0'            |            | '0'        | FULLY_DISABLED  ",
    "'sys=null env=invalid'      |            | 'invalid'  | FULLY_DISABLED  ",
    "'sys=null env=true'         |            | 'true'     | FULLY_ENABLED   ",
    "'sys=null env=1'            |            | '1'        | FULLY_ENABLED   ",
    "'sys=empty env=null'        | ''         |            | ENABLED_INACTIVE",
    "'sys=empty env=empty'       | ''         | ''         | ENABLED_INACTIVE",
    "'sys=empty env=inactive'    | ''         | 'inactive' | ENABLED_INACTIVE",
    "'sys=empty env=false'       | ''         | 'false'    | FULLY_DISABLED  ",
    "'sys=empty env=0'           | ''         | '0'        | FULLY_DISABLED  ",
    "'sys=empty env=invalid'     | ''         | 'invalid'  | FULLY_DISABLED  ",
    "'sys=empty env=true'        | ''         | 'true'     | FULLY_ENABLED   ",
    "'sys=empty env=1'           | ''         | '1'        | FULLY_ENABLED   ",
    "'sys=inactive env=null'     | 'inactive' |            | ENABLED_INACTIVE",
    "'sys=inactive env=empty'    | 'inactive' | ''         | ENABLED_INACTIVE",
    "'sys=inactive env=inactive' | 'inactive' | 'inactive' | ENABLED_INACTIVE",
    "'sys=inactive env=false'    | 'inactive' | 'false'    | ENABLED_INACTIVE",
    "'sys=inactive env=0'        | 'inactive' | '0'        | ENABLED_INACTIVE",
    "'sys=inactive env=invalid'  | 'inactive' | 'invalid'  | ENABLED_INACTIVE",
    "'sys=inactive env=true'     | 'inactive' | 'true'     | ENABLED_INACTIVE",
    "'sys=inactive env=1'        | 'inactive' | '1'        | ENABLED_INACTIVE",
    "'sys=false env=null'        | 'false'    |            | FULLY_DISABLED  ",
    "'sys=false env=empty'       | 'false'    | ''         | FULLY_DISABLED  ",
    "'sys=false env=inactive'    | 'false'    | 'inactive' | FULLY_DISABLED  ",
    "'sys=false env=false'       | 'false'    | 'false'    | FULLY_DISABLED  ",
    "'sys=false env=0'           | 'false'    | '0'        | FULLY_DISABLED  ",
    "'sys=false env=invalid'     | 'false'    | 'invalid'  | FULLY_DISABLED  ",
    "'sys=false env=true'        | 'false'    | 'true'     | FULLY_DISABLED  ",
    "'sys=false env=1'           | 'false'    | '1'        | FULLY_DISABLED  ",
    "'sys=0 env=null'            | '0'        |            | FULLY_DISABLED  ",
    "'sys=true env=null'         | 'true'     |            | FULLY_ENABLED   ",
    "'sys=true env=empty'        | 'true'     | ''         | FULLY_ENABLED   ",
    "'sys=true env=inactive'     | 'true'     | 'inactive' | FULLY_ENABLED   ",
    "'sys=true env=false'        | 'true'     | 'false'    | FULLY_ENABLED   ",
    "'sys=true env=0'            | 'true'     | '0'        | FULLY_ENABLED   ",
    "'sys=true env=invalid'      | 'true'     | 'invalid'  | FULLY_ENABLED   ",
    "'sys=true env=true'         | 'true'     | 'true'     | FULLY_ENABLED   ",
    "'sys=true env=1'            | 'true'     | '1'        | FULLY_ENABLED   ",
    "'sys=1 env=null'            | '1'        |            | FULLY_ENABLED   "
  })
  void appsecStateWithSysAndEnv(String sys, String env, ProductActivation expected)
      throws Exception {
    if (sys != null) {
      System.setProperty(PREFIX + APPSEC_ENABLED, sys);
    }
    if (env != null) {
      setEnv("DD_APPSEC_ENABLED", env);
    }

    Config config = newConfig();

    assertEquals(expected, config.getAppSecActivation());
  }

  @Test
  void hostnameDiscoveryWithEnvironmentVariables() {
    String expectedHostname = "myhostname";
    setEnv("HOSTNAME", expectedHostname);
    setEnv("COMPUTERNAME", expectedHostname);

    String hostname = Config.initHostName();

    assertEquals(expectedHostname, hostname);
  }

  @Test
  void hostnameDiscoveryWithoutEnvironmentVariables() {
    setEnv("HOSTNAME", "");
    setEnv("COMPUTERNAME", "");

    String hostname = Config.initHostName();

    assertNotNull(hostname);
    assertFalse(hostname.trim().isEmpty());
  }

  @Test
  void configInstantiationShouldNotFailIfLlmObsIsEnabledViaSysPropAndMlAppIsNotSet()
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty(LLMOBS_ENABLED, "true");
    properties.setProperty(SERVICE, "test-service");

    Config config = newConfig(withPropertiesOverride(properties));

    assertTrue(config.isLlmObsEnabled());
    assertEquals("test-service", config.getLlmObsMlApp());
  }

  @Test
  void configInstantiationShouldNotFailIfLlmObsIsEnabledViaSysPropAndMlAppIsEmpty()
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty(LLMOBS_ENABLED, "true");
    properties.setProperty(SERVICE, "test-service");
    properties.setProperty(LLMOBS_ML_APP, "");

    Config config = newConfig(withPropertiesOverride(properties));

    assertTrue(config.isLlmObsEnabled());
    assertEquals("test-service", config.getLlmObsMlApp());
  }

  @Test
  void configInstantiationShouldNotFailIfLlmObsIsEnabledViaEnvVarAndMlAppIsNotSet()
      throws Exception {
    setEnv(DD_LLMOBS_ENABLED_ENV, "true");
    setEnv(DD_SERVICE_NAME_ENV, "test-service");

    Config config = newConfig();

    assertTrue(config.isLlmObsEnabled());
    assertEquals("test-service", config.getLlmObsMlApp());
  }

  @Test
  void configInstantiationShouldNotFailIfLlmObsIsEnabledViaEnvVarAndMlAppIsEmpty()
      throws Exception {
    setEnv(DD_LLMOBS_ENABLED_ENV, "true");
    setEnv(DD_SERVICE_NAME_ENV, "test-service");
    setEnv(DD_LLMOBS_ML_APP_ENV, "");

    Config config = newConfig();

    assertTrue(config.isLlmObsEnabled());
    assertEquals("test-service", config.getLlmObsMlApp());
  }

  @Test
  void lambdaStripInjectedContextIsDisabledByDefault() throws Exception {
    Config config = newConfig();

    assertFalse(config.isLambdaStripInjectedContextEnabled());
  }

  @Test
  void lambdaStripInjectedContextCanBeEnabled() throws Exception {
    Properties properties = new Properties();
    properties.setProperty(TRACE_AWS_LAMBDA_STRIP_CONTEXT_ENABLED, "true");

    Config config = newConfig(withPropertiesOverride(properties));

    assertTrue(config.isLambdaStripInjectedContextEnabled());
  }

  @Test
  void configInstantiationShouldNotFailIfLlmObsIsEnabledAgentlessDisabledViaSysPropAndMlAppIsSet()
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty(LLMOBS_ENABLED, "true");
    properties.setProperty(LLMOBS_AGENTLESS_ENABLED, "false");
    properties.setProperty(LLMOBS_ML_APP, "test-ml-app");

    Config config = newConfig(withPropertiesOverride(properties));

    assertTrue(config.isLlmObsEnabled());
    assertFalse(config.isLlmObsAgentlessEnabled());
    assertEquals("test-ml-app", config.getLlmObsMlApp());
  }

  @Test
  void configInstantiationShouldNotFailIfLlmObsIsEnabledAgentlessDisabledViaEnvVarAndMlAppIsSet()
      throws Exception {
    setEnv(DD_LLMOBS_ENABLED_ENV, "true");
    setEnv(DD_LLMOBS_ML_APP_ENV, "test-ml-app");

    Config config = newConfig();

    assertTrue(config.isLlmObsEnabled());
    assertFalse(config.isLlmObsAgentlessEnabled());
    assertEquals("test-ml-app", config.getLlmObsMlApp());
  }

  @Test
  void configInstantiationShouldFailIfLlmObsIsInAgentlessModeViaSysPropAndApiKeyIsNotSet() {
    Properties properties = new Properties();
    properties.setProperty(LLMOBS_ENABLED, "true");
    properties.setProperty(LLMOBS_AGENTLESS_ENABLED, "true");
    properties.setProperty(LLMOBS_ML_APP, "test-ml-app");

    assertThrows(
        FatalAgentMisconfigurationError.class, () -> newConfig(withPropertiesOverride(properties)));
  }

  @Test
  void configInstantiationShouldFailIfLlmObsIsInAgentlessModeViaEnvVarAndApiKeyIsNotSet() {
    setEnv(DD_LLMOBS_ENABLED_ENV, "true");
    setEnv(DD_LLMOBS_ML_APP_ENV, "a");
    setEnv(DD_LLMOBS_AGENTLESS_ENABLED_ENV, "true");

    assertThrows(FatalAgentMisconfigurationError.class, () -> newConfig());
  }

  @Test
  void
      configInstantiationShouldNotFailIfLlmObsIsEnabledAgentlessEnabledAndApiKeyAndMlAppAreSetViaSysProp()
          throws Exception {
    Properties properties = new Properties();
    properties.setProperty(LLMOBS_ENABLED, "true");
    properties.setProperty(LLMOBS_AGENTLESS_ENABLED, "true");
    properties.setProperty(LLMOBS_ML_APP, "test-ml-app");
    properties.setProperty(API_KEY, "123456789");

    Config config = newConfig(withPropertiesOverride(properties));

    assertTrue(config.isLlmObsEnabled());
    assertTrue(config.isLlmObsAgentlessEnabled());
    assertEquals("test-ml-app", config.getLlmObsMlApp());
  }

  @Test
  void
      configInstantiationShouldNotFailIfLlmObsIsEnabledAgentlessEnabledAndApiKeyAndMlAppAreSetViaEnvVar()
          throws Exception {
    setEnv(DD_LLMOBS_ENABLED_ENV, "true");
    setEnv(DD_LLMOBS_ML_APP_ENV, "a");
    setEnv(DD_LLMOBS_AGENTLESS_ENABLED_ENV, "true");
    setEnv(DD_API_KEY_ENV, "8663294466");

    Config config = newConfig();

    assertTrue(config.isLlmObsEnabled());
    assertTrue(config.isLlmObsAgentlessEnabled());
    assertEquals("a", config.getLlmObsMlApp());
  }

  @Test
  void configInstantiationShouldFailIfCiVisibilityAgentlessModeIsEnabledAndApiKeyIsNotSet() {
    Properties properties = new Properties();
    properties.setProperty(CIVISIBILITY_ENABLED, "true");
    properties.setProperty(CIVISIBILITY_AGENTLESS_ENABLED, "true");

    assertThrows(
        FatalAgentMisconfigurationError.class, () -> newConfig(withPropertiesOverride(properties)));
  }

  @Test
  void configInstantiationShouldNotFailIfCiVisibilityAgentlessModeIsEnabledAndApiKeyIsSet()
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty(CIVISIBILITY_ENABLED, "true");
    properties.setProperty(CIVISIBILITY_AGENTLESS_ENABLED, "true");
    properties.setProperty(API_KEY, "123456789");

    Config config = newConfig(withPropertiesOverride(properties));

    assertTrue(config.isCiVisibilityEnabled());
    assertTrue(config.isCiVisibilityAgentlessEnabled());
  }

  @TableTest({
    "scenario                    | ciVisibility | agentless | serviceDiscovery | expected",
    "default                     | false        | false     |                  | true    ",
    "CI Visibility with agent    | true         | false     |                  | true    ",
    "CI Visibility agentless     | true         | true      |                  | false   ",
    "explicitly enabled override | true         | true      | true             | true    "
  })
  @ParameterizedTest(name = "[{index}] {0}")
  void serviceDiscoveryIsDisabledInCiVisibilityAgentlessMode(
      String scenario,
      boolean ciVisibility,
      boolean agentless,
      String serviceDiscovery,
      boolean expected)
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty(CIVISIBILITY_ENABLED, String.valueOf(ciVisibility));
    properties.setProperty(CIVISIBILITY_AGENTLESS_ENABLED, String.valueOf(agentless));
    properties.setProperty(API_KEY, "123456789");
    if (serviceDiscovery != null) {
      properties.setProperty(TRACE_SERVICE_DISCOVERY_ENABLED, serviceDiscovery);
    }

    Config config = newConfig(withPropertiesOverride(properties));

    assertEquals(expected, config.isServiceDiscoveryEnabled());
  }

  @TableTest({
    "scenario        | configuredBuckets | expectedBuckets",
    "'5,4,3,2,1'     | '5,4,3,2,1'       | [5, 4, 3, 2, 1]",
    "'5, 4, 3, 2, 1' | '5, 4, 3, 2, 1'   | [5, 4, 3, 2, 1]",
    "'5,4,3,2'       | '5,4,3,2'         |                ",
    "'5,4,3,2,1,'    | '5,4,3,2,1,'      |                ",
    "'5,4,3,2,0'     | '5,4,3,2,0'       |                ",
    "'5,4,3,2,21'    | '5,4,3,2,21'      |                ",
    "'5,4,3,2,nope'  | '5,4,3,2,nope'    |                "
  })
  void dynamicAtrBucketsAreParsedByConfig(String configuredBuckets, List<Integer> expectedBuckets)
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty(CIVISIBILITY_DYNAMIC_ATR_ENABLED, "true");
    properties.setProperty(CIVISIBILITY_DYNAMIC_ATR_BUCKETS, configuredBuckets);

    Config config = newConfig(withPropertiesOverride(properties));

    assertEquals(expectedBuckets, config.getCiVisibilityDynamicAtrBuckets());
  }

  @Test
  void dynamicAtrBucketsAreIgnoredWhenDynamicAtrIsDisabled() throws Exception {
    Properties properties = new Properties();
    properties.setProperty(CIVISIBILITY_DYNAMIC_ATR_BUCKETS, "5,4,3,2,1");

    assertNull(newConfig(withPropertiesOverride(properties)).getCiVisibilityDynamicAtrBuckets());
  }

  public static class ClassThrowsExceptionForValueOfMethod {
    public static ClassThrowsExceptionForValueOfMethod valueOf(String ignored) throws Throwable {
      throw new Throwable();
    }
  }

  /** Names a class argument by its simple name for readable test display names. */
  private static Named<Class<?>> type(Class<?> tClass) {
    return named(tClass.getSimpleName(), tClass);
  }

  /** Calls the package-private {@code ConfigConverter.valueOf(String, Class)}. */
  private static Object configConverterValueOf(String value, Class<?> tClass)
      throws ReflectiveOperationException {
    Method valueOf =
        Class.forName("datadog.trace.bootstrap.config.provider.ConfigConverter")
            .getDeclaredMethod("valueOf", String.class, Class.class);
    valueOf.setAccessible(true);
    try {
      return valueOf.invoke(null, value, tClass);
    } catch (InvocationTargetException e) {
      throw rethrowCause(e);
    }
  }

  // spotless:off
  @TableTest({
    "scenario                      | pse     | psi     | tps      | tpse             | tpsi    | expectedPse | expectedPsi | expectedTpse                     | expectedTpsi",
    "legacy datadog extract        | DATADOG | B3      |          |                  |         | [DATADOG]   | [B3]        | [DATADOG]                        | [B3SINGLE, B3MULTI]",
    "legacy b3 extract             | B3      | DATADOG |          |                  |         | [B3]        | [DATADOG]   | [B3SINGLE, B3MULTI]              | [DATADOG]",
    "style overrides legacy        | B3      | DATADOG | haystack |                  |         | [B3]        | [DATADOG]   | [HAYSTACK]                       | [HAYSTACK]",
    "extract overrides style       | B3      | DATADOG | haystack | b3single         |         | [B3]        | [DATADOG]   | [B3SINGLE]                       | [HAYSTACK]",
    "inject overrides style        | B3      | DATADOG | haystack |                  | b3multi | [B3]        | [DATADOG]   | [HAYSTACK]                       | [B3MULTI]",
    "extract and inject override   | B3      | DATADOG | haystack | b3single         | b3multi | [B3]        | [DATADOG]   | [B3SINGLE]                       | [B3MULTI]",
    "extract and inject no style   | B3      | DATADOG |          | b3single         | b3multi | [B3]        | [DATADOG]   | [B3SINGLE]                       | [B3MULTI]",
    "style only                    |         |         | haystack |                  |         | [DATADOG]   | [DATADOG]   | [HAYSTACK]                       | [HAYSTACK]",
    "style with extract and inject |         |         | haystack | b3single         | b3multi | [DATADOG]   | [DATADOG]   | [B3SINGLE]                       | [B3MULTI]",
    "extract and inject only       |         |         |          | b3single         | b3multi | [DATADOG]   | [DATADOG]   | [B3SINGLE]                       | [B3MULTI]",
    "nothing set                   |         |         |          |                  |         | [DATADOG]   | [DATADOG]   | [DATADOG, TRACECONTEXT, BAGGAGE] | [DATADOG, TRACECONTEXT, BAGGAGE]",
    "nothing set again             |         |         |          |                  |         | [DATADOG]   | [DATADOG]   | [DATADOG, TRACECONTEXT, BAGGAGE] | [DATADOG, TRACECONTEXT, BAGGAGE]",
    "b3 single header extract      |         |         |          | b3 single header |         | [DATADOG]   | [DATADOG]   | [B3SINGLE]                       | [DATADOG, TRACECONTEXT, BAGGAGE]",
    "b3 extract                    |         |         |          | b3               |         | [DATADOG]   | [DATADOG]   | [B3MULTI]                        | [DATADOG, TRACECONTEXT, BAGGAGE]"
  })
  // spotless:on
  void checkTracePropagationStyleOverridesFor(
      String pse,
      String psi,
      String tps,
      String tpse,
      String tpsi,
      List<PropagationStyle> expectedPse,
      List<PropagationStyle> expectedPsi,
      List<TracePropagationStyle> expectedTpse,
      List<TracePropagationStyle> expectedTpsi)
      throws Exception {
    if (pse != null) {
      setEnv("DD_PROPAGATION_STYLE_EXTRACT", pse);
    }
    if (psi != null) {
      setEnv("DD_PROPAGATION_STYLE_INJECT", psi);
    }
    if (tps != null) {
      setEnv("DD_TRACE_PROPAGATION_STYLE", tps);
    }
    if (tpse != null) {
      setEnv("DD_TRACE_PROPAGATION_STYLE_EXTRACT", tpse);
    }
    if (tpsi != null) {
      setEnv("DD_TRACE_PROPAGATION_STYLE_INJECT", tpsi);
    }

    Config config = newConfig();

    assertEquals(expectedPse, new ArrayList<>(config.getPropagationStylesToExtract()));
    assertEquals(expectedPsi, new ArrayList<>(config.getPropagationStylesToInject()));
    assertEquals(expectedTpse, new ArrayList<>(config.getTracePropagationStylesToExtract()));
    assertEquals(expectedTpsi, new ArrayList<>(config.getTracePropagationStylesToInject()));
  }

  @Test
  void agentArgsAreUsedByConfig() throws Exception {
    AgentArgsInjector.injectAgentArgsConfig(
        singletonMap(PREFIX + SERVICE_NAME, "args service name"));

    Config config = newConfig();

    assertEquals("args service name", config.getServiceName());
  }

  @Test
  void agentArgsOverrideCapturedEnvProps() throws Exception {
    AgentArgsInjector.injectAgentArgsConfig(
        singletonMap(PREFIX + SERVICE_NAME, "args service name"));

    useFixedEnv(singletonMap(SERVICE_NAME, "captured props service name"));

    Config config = newConfig();

    assertEquals("args service name", config.getServiceName());
  }

  @Test
  void sysPropsOverrideAgentArgs() throws Exception {
    System.setProperty(PREFIX + SERVICE_NAME, "system prop service name");

    AgentArgsInjector.injectAgentArgsConfig(
        singletonMap(PREFIX + SERVICE_NAME, "args service name"));

    Config config = newConfig();

    assertEquals("system prop service name", config.getServiceName());
  }

  @Test
  void envVarsOverrideAgentArgs() throws Exception {
    setEnv(DD_SERVICE_NAME_ENV, "env service name");

    AgentArgsInjector.injectAgentArgsConfig(
        singletonMap(PREFIX + SERVICE_NAME, "args service name"));

    Config config = newConfig();

    assertEquals("env service name", config.getServiceName());
  }

  @Test
  void agentArgsAreUsedForLookingUpPropertiesFile() throws Exception {
    AgentArgsInjector.injectAgentArgsConfig(
        singletonMap(PREFIX + CONFIGURATION_FILE, "src/test/resources/dd-java-tracer.properties"));

    Config config = newConfig();

    assertEquals("src/test/resources/dd-java-tracer.properties", config.getConfigFileStatus());
    assertEquals("set-in-properties", config.getServiceName());
  }

  @Test
  void fallbackToPropertiesFileHasLowerPriorityThanAgentArgs() throws Exception {
    Map<String, String> agentArgs = new HashMap<>();
    agentArgs.put(PREFIX + CONFIGURATION_FILE, "src/test/resources/dd-java-tracer.properties");
    agentArgs.put(PREFIX + SERVICE_NAME, "args service name");
    AgentArgsInjector.injectAgentArgsConfig(agentArgs);

    Config config = newConfig();

    assertEquals("src/test/resources/dd-java-tracer.properties", config.getConfigFileStatus());
    assertEquals("args service name", config.getServiceName());
  }

  @ParameterizedTest(name = "configured {0}")
  @MethodSource("longRunningTraceInvalidInitialFlushIntervalSetToDefaultArguments")
  void longRunningTraceInvalidInitialFlushIntervalSetToDefault(
      String configuredFlushInterval, long flushInterval) {
    Properties prop = new Properties();
    prop.setProperty(TRACE_LONG_RUNNING_ENABLED, "true");
    prop.setProperty(TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL, configuredFlushInterval);
    Config config = Config.get(prop);

    assertTrue(config.isLongRunningTraceEnabled());
    assertEquals(flushInterval, config.getLongRunningTraceInitialFlushInterval());
  }

  static Stream<Arguments> longRunningTraceInvalidInitialFlushIntervalSetToDefaultArguments() {
    return Stream.of(
        arguments("invalid", DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL),
        arguments("-1", DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL),
        arguments("9", DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL),
        arguments("451", DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL),
        arguments("10", 10L),
        arguments("450", 450L));
  }

  @Test
  void ssiInjectionEnabled() throws Exception {
    Properties prop = new Properties();
    prop.setProperty(SSI_INJECTION_ENABLED, "tracer");
    Config config = Config.get(prop);

    assertEquals("tracer", fieldValue(config, "ssiInjectionEnabled"));
  }

  @Test
  void ssiInjectForce() throws Exception {
    Properties prop = new Properties();
    prop.setProperty(SSI_INJECTION_FORCE, "true");
    Config config = Config.get(prop);

    assertEquals(true, fieldValue(config, "ssiInjectionForce"));
  }

  @Test
  void instrumentationSource() throws Exception {
    Properties prop = new Properties();
    prop.setProperty(INSTRUMENTATION_SOURCE, "ssi");
    Config config = Config.get(prop);

    assertEquals("ssi", fieldValue(config, "instrumentationSource"));
  }

  @ParameterizedTest(name = "configured {0}")
  @MethodSource("longRunningTraceInvalidFlushIntervalSetToDefaultArguments")
  void longRunningTraceInvalidFlushIntervalSetToDefault(
      String configuredFlushInterval, long flushInterval) {
    Properties prop = new Properties();
    prop.setProperty(TRACE_LONG_RUNNING_ENABLED, "true");
    prop.setProperty(TRACE_LONG_RUNNING_FLUSH_INTERVAL, configuredFlushInterval);
    Config config = Config.get(prop);

    assertTrue(config.isLongRunningTraceEnabled());
    assertEquals(flushInterval, config.getLongRunningTraceFlushInterval());
  }

  static Stream<Arguments> longRunningTraceInvalidFlushIntervalSetToDefaultArguments() {
    return Stream.of(
        arguments("invalid", DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL),
        arguments("-1", DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL),
        arguments("19", DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL),
        arguments("451", DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL),
        arguments("20", 20L),
        arguments("450", 450L));
  }

  @TableTest({
    "scenario                   | configuredPartialEnabled | configuredPartialMinSpans | partialMinSpans",
    "disabled without min spans | false                    |                           | 0              ",
    "min spans only             |                          | 47                        | 47             ",
    "enabled with min spans     | true                     | 11                        | 11             ",
    "disabled with min spans    | false                    | 17                        | 0              "
  })
  @MethodSource("partialFlushAndMinSpansInteractionArguments")
  void partialFlushAndMinSpansInteraction(
      Boolean configuredPartialEnabled, Integer configuredPartialMinSpans, int partialMinSpans) {
    Properties prop = new Properties();
    if (configuredPartialEnabled != null) {
      prop.setProperty(PARTIAL_FLUSH_ENABLED, configuredPartialEnabled.toString());
    }
    if (configuredPartialMinSpans != null) {
      prop.setProperty(PARTIAL_FLUSH_MIN_SPANS, configuredPartialMinSpans.toString());
    }
    Config config = Config.get(prop);

    assertEquals(partialMinSpans, config.getPartialFlushMinSpans());
  }

  static Stream<Arguments> partialFlushAndMinSpansInteractionArguments() {
    return Stream.of(
        arguments(named("nothing set", null), null, DEFAULT_PARTIAL_FLUSH_MIN_SPANS),
        arguments(named("enabled without min spans", true), null, DEFAULT_PARTIAL_FLUSH_MIN_SPANS));
  }

  // spotless:off
  @TableTest({
    "scenario | enablementMode | expectedEnabled | expectedStartDelay | expectedStartForceFirst",
    "enabled  | true           | true            | 1                  | true",
    "disabled | false          | false           | 1                  | true"
  })
  // spotless:on
  @MethodSource("checkProfilingSsiAutoEnablementArguments")
  void checkProfilingSsiAutoEnablement(
      String enablementMode,
      boolean expectedEnabled,
      int expectedStartDelay,
      boolean expectedStartForceFirst) {
    Properties prop = new Properties();
    prop.setProperty(PROFILING_ENABLED, enablementMode);
    prop.setProperty(PROFILING_START_DELAY, "1");
    prop.setProperty(PROFILING_START_FORCE_FIRST, "true");

    Config config = Config.get(prop);

    assertEquals(expectedEnabled, config.isProfilingEnabled());
    assertEquals(expectedStartDelay, config.getProfilingStartDelay());
    assertEquals(expectedStartForceFirst, config.isProfilingStartForceFirst());
  }

  static Stream<Arguments> checkProfilingSsiAutoEnablementArguments() {
    return Stream.of(
        arguments(
            named("auto", "auto"),
            true,
            PROFILING_START_DELAY_DEFAULT,
            PROFILING_START_FORCE_FIRST_DEFAULT));
  }

  @Test
  void urlForDebuggerWithUnixDomainSocket() {
    Properties prop = new Properties();
    prop.setProperty(AGENT_HOST, "myhost");
    prop.setProperty(TRACE_AGENT_PORT, "1234");
    prop.setProperty(TRACE_AGENT_URL, "unix:///path/to/socket");

    Config config = Config.get(prop);

    assertEquals("http://localhost:8126/symdb/v1/input", config.getFinalDebuggerSymDBUrl());
  }

  @Test
  void verifyTryCatchBehaviorForInvalidStringsForTracePropagationBehaviorExtract() {
    Properties prop = new Properties();
    prop.setProperty(TRACE_PROPAGATION_BEHAVIOR_EXTRACT, "test");

    Config config = Config.get(prop);

    assertEquals(
        TracePropagationBehaviorExtract.CONTINUE, config.getTracePropagationBehaviorExtract());
  }

  @TableTest({
    "scenario          | site              | expectedUrl                                                                   ",
    "datadoghq.com     | datadoghq.com     | https://instrumentation-telemetry-intake.datadoghq.com/api/v2/apmtelemetry    ",
    "us3.datadoghq.com | us3.datadoghq.com | https://instrumentation-telemetry-intake.us3.datadoghq.com/api/v2/apmtelemetry",
    "us5.datadoghq.com | us5.datadoghq.com | https://instrumentation-telemetry-intake.us5.datadoghq.com/api/v2/apmtelemetry",
    "ap1.datadoghq.com | ap1.datadoghq.com | https://instrumentation-telemetry-intake.ap1.datadoghq.com/api/v2/apmtelemetry",
    "datadoghq.eu      | datadoghq.eu      | https://instrumentation-telemetry-intake.datadoghq.eu/api/v2/apmtelemetry     ",
    "datad0g.com       | datad0g.com       | https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry                  "
  })
  void intakeClientUsesCorrectUrlForSite(String site, String expectedUrl) {
    Config config = spy(Config.get());

    doReturn(site).when(config).getSite();

    assertEquals(expectedUrl, config.getDefaultTelemetryUrl());
  }

  @TableTest({
    "scenario               | reqEnv        | respEnv       | expectedReqConfig | expectedRespConfig",
    "all                    | all           | all           | '[]'              | '[]'              ",
    "all with invalid       | 'all,invalid' | 'all,invalid' |                   |                   ",
    "empty                  | ''            | ''            |                   |                   ",
    "invalid                | invalid       | invalid       |                   |                   ",
    "json path              | '$.a'         | '$.b'         | \"[$['a']]\"      | \"[$['b']]\"      ",
    "json path with invalid | '$.a,invalid' | '$.b,invalid' | \"[$['a']]\"      | \"[$['b']]\"      "
  })
  void setCloudPayloadTaggingConfig(
      String reqEnv, String respEnv, String expectedReqConfig, String expectedRespConfig)
      throws Exception {
    setEnv(DD_TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING_ENV, reqEnv);
    setEnv(DD_TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING_ENV, respEnv);

    Config config = newConfig();

    if (expectedReqConfig == null) {
      // if expected config is null, then the feature should be disabled
      assertFalse(config.isCloudRequestPayloadTaggingEnabled());
    } else {
      assertEquals(expectedReqConfig, config.getCloudRequestPayloadTagging().toString());
    }

    if (expectedRespConfig == null) {
      assertFalse(config.isCloudResponsePayloadTaggingEnabled());
    } else {
      assertEquals(expectedRespConfig, config.getCloudResponsePayloadTagging().toString());
    }
  }

  @ParameterizedTest(name = "[{index}] maxItems={0}, maxBytes={1}")
  @MethodSource("baggageLimitsNormalizeNegativeValuesToZeroArguments")
  void baggageLimitsNormalizeNegativeValuesToZero(
      String maxItems, String maxBytes, int expectedMaxItems, int expectedMaxBytes) {
    Properties prop = new Properties();
    if (maxItems != null) {
      prop.setProperty(TRACE_BAGGAGE_MAX_ITEMS, maxItems);
    }
    if (maxBytes != null) {
      prop.setProperty(TRACE_BAGGAGE_MAX_BYTES, maxBytes);
    }

    Config config = Config.get(prop);

    assertEquals(expectedMaxItems, config.getTraceBaggageMaxItems());
    assertEquals(expectedMaxBytes, config.getTraceBaggageMaxBytes());
  }

  static Stream<Arguments> baggageLimitsNormalizeNegativeValuesToZeroArguments() {
    return Stream.of(
        arguments(null, null, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, DEFAULT_TRACE_BAGGAGE_MAX_BYTES),
        arguments("0", "0", 0, 0),
        arguments("8", "512", 8, 512),
        arguments("-1", null, 0, DEFAULT_TRACE_BAGGAGE_MAX_BYTES),
        arguments(null, "-8192", DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, 0),
        arguments("-1", "-1", 0, 0));
  }

  @TableTest({
    "scenario                                 | sys   | env   | expected",
    "default is true                          |       |       | true    ",
    "env true                                 |       | true  | true    ",
    "env false                                |       | false | false   ",
    "sys true                                 | true  |       | true    ",
    "sys false                                | false |       | false   ",
    "sys prop takes precedence over env false | true  | false | true    ",
    "sys prop takes precedence over env true  | false | true  | false   "
  })
  void dbMetadataFetchingEnabledWithSysEnv(String sys, String env, boolean expected)
      throws Exception {
    if (sys != null) {
      System.setProperty(PREFIX + DB_METADATA_FETCHING_ON_QUERY, sys);
    }
    if (env != null) {
      setEnv("DD_TRACE_DB_METADATA_FETCHING_ON_QUERY", env);
    }

    Config config = newConfig();

    assertEquals(expected, config.isDbMetadataFetchingOnQueryEnabled());
  }

  @TableTest({
    "scenario                                 | sys   | env   | expected",
    "default is true                          |       |       | true    ",
    "env true                                 |       | true  | true    ",
    "env false                                |       | false | false   ",
    "sys true                                 | true  |       | true    ",
    "sys false                                | false |       | false   ",
    "sys prop takes precedence over env false | true  | false | true    ",
    "sys prop takes precedence over env true  | false | true  | false   "
  })
  void dbClientInfoFetchingEnabledWithSysEnv(String sys, String env, boolean expected)
      throws Exception {
    if (sys != null) {
      System.setProperty(PREFIX + DB_METADATA_FETCHING_ON_CONNECT, sys);
    }
    if (env != null) {
      setEnv("DD_TRACE_DB_METADATA_FETCHING_ON_CONNECT", env);
    }

    Config config = newConfig();

    assertEquals(expected, config.isDbMetadataFetchingOnConnectEnabled());
  }

  @TableTest({
    "scenario | configured | expected",
    "not set  |            | false   ",
    "false    | false      | false   ",
    "true     | true       | true    "
  })
  void oracleDbmActionPropagationEnabled(String configured, boolean expected) throws Exception {
    Properties properties = new Properties();
    if (configured != null) {
      properties.setProperty(DB_DBM_PROPAGATION_ORACLE_ACTION_ONLY_ENABLED, configured);
    }

    Config config = newConfig(withPropertiesOverride(properties));

    assertEquals(expected, config.isDbmPropagationOracleActionOnlyEnabled());
  }

  // Table from requirements:
  // DD_APPSEC_ENABLED \ DD_TRACE_RESOURCE_RENAMING_ENABLED | unset   | true    | false
  // ---------------------------------------------------------------------------
  // unset (inactive)                                        | disabled| enabled | disabled
  // true                                                    | enabled | enabled | disabled
  // false                                                   | disabled| enabled | disabled
  @TableTest({
    "scenario                    | appsec   | explicit | expected",
    "unset + unset = disabled    |          |          | false   ",
    "unset + true = enabled      |          | true     | true    ",
    "unset + false = disabled    |          | false    | false   ",
    "inactive + unset = disabled | inactive |          | false   ",
    "inactive + true = enabled   | inactive | true     | true    ",
    "inactive + false = disabled | inactive | false    | false   ",
    "true + unset = enabled      | true     |          | true    ",
    "true + true = enabled       | true     | true     | true    ",
    "true + false = disabled     | true     | false    | false   ",
    "1 + unset = enabled         | 1        |          | true    ",
    "1 + true = enabled          | 1        | true     | true    ",
    "1 + false = disabled        | 1        | false    | false   ",
    "false + unset = disabled    | false    |          | false   ",
    "false + true = enabled      | false    | true     | true    ",
    "false + false = disabled    | false    | false    | false   ",
    "0 + unset = disabled        | 0        |          | false   ",
    "0 + true = enabled          | 0        | true     | true    ",
    "0 + false = disabled        | 0        | false    | false   "
  })
  void traceResourceRenamingActivationWithAppsecAndExplicit(
      String appsec, String explicit, boolean expected) throws Exception {
    if (appsec != null) {
      setEnv("DD_APPSEC_ENABLED", appsec);
    }
    if (explicit != null) {
      setEnv("DD_TRACE_RESOURCE_RENAMING_ENABLED", explicit);
    }

    Config config = newConfig();

    assertEquals(expected, config.isTraceResourceRenamingEnabled());
  }

  @TableTest({
    "scenario | value | expected",
    "not set  |       | false   ",
    "true     | true  | true    ",
    "false    | false | false   ",
    "1        | 1     | true    ",
    "0        | 0     | false   "
  })
  void traceResourceRenamingAlwaysSimplifiedEndpointWithValue(String value, boolean expected)
      throws Exception {
    if (value != null) {
      setEnv("DD_TRACE_RESOURCE_RENAMING_ALWAYS_SIMPLIFIED_ENDPOINT", value);
    }

    Config config = newConfig();

    assertEquals(expected, config.isTraceResourceRenamingAlwaysSimplifiedEndpoint());
  }

  @TableTest({
    "scenario             | value             | expected     ",
    "not set              |                   | agentless    ",
    "empty                | ''                | agentless    ",
    "blank                | '   '             | agentless    ",
    "case insensitive     | ReMoTe_ConFiG     | remote_config",
    "padded               | ' remote_config ' | invalid      ",
    "unknown source       | not-a-real-source | invalid      ",
    "unset is not a value | unset             | invalid      "
  })
  void featureFlagConfigurationSourceParsesValue(String value, String expected) throws Exception {
    Properties properties = new Properties();
    if (value != null) {
      properties.setProperty(FEATURE_FLAGS_CONFIGURATION_SOURCE, value);
    }

    Config config = newConfig(withPropertiesOverride(properties));

    assertEquals(expected, config.getFeatureFlaggingConfigurationSource());
  }

  @TableTest({
    "scenario                           | providerEnabled | source        | legacyProviderEnabled | expectedEnabled | expectedSource",
    "nothing set                        |                 |               |                       | true            | agentless     ",
    "provider enabled                   | true            |               |                       | true            | agentless     ",
    "legacy enabled                     |                 |               | true                  | true            | remote_config ",
    "legacy disabled                    |                 |               | false                 | false           |               ",
    "source wins over legacy enabled    |                 | agentless     | true                  | true            | agentless     ",
    "source wins over legacy disabled   |                 | remote_config | false                 | true            | remote_config ",
    "provider disabled wins over legacy | false           | agentless     | true                  | false           | agentless     ",
    "legacy disabled wins over provider | true            |               | false                 | false           |               ",
    "unknown source                     |                 | not-a-source  |                       | false           | invalid       ",
    "offline source                     |                 | offline       | true                  | false           | invalid       "
  })
  void featureFlagConfigurationAppliesMigrationPrecedence(
      Boolean providerEnabled,
      String source,
      Boolean legacyProviderEnabled,
      boolean expectedEnabled,
      String expectedSource)
      throws Exception {
    Properties properties = new Properties();
    if (providerEnabled != null) {
      properties.setProperty(FEATURE_FLAGS_ENABLED, providerEnabled.toString());
    }
    if (source != null) {
      properties.setProperty(FEATURE_FLAGS_CONFIGURATION_SOURCE, source);
    }
    if (legacyProviderEnabled != null) {
      properties.setProperty(
          EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED, legacyProviderEnabled.toString());
    }

    Config config = newConfig(withPropertiesOverride(properties));

    assertEquals(expectedEnabled, config.isFeatureFlaggingProviderEnabled());
    assertEquals(expectedSource, config.getFeatureFlaggingConfigurationSource());
  }

  /** Reads a private {@link Config} field that has no getter. */
  private static Object fieldValue(Config config, String fieldName)
      throws ReflectiveOperationException {
    Field field = Config.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(config);
  }

  /**
   * Creates a new {@link Config} from the current system properties and environment variables,
   * without replacing the global instance.
   */
  private static Config newConfig() throws ReflectiveOperationException {
    return instantiate(Config.class.getDeclaredConstructor());
  }

  /** Creates a new {@link Config} backed by the given provider. */
  private static Config newConfig(ConfigProvider configProvider)
      throws ReflectiveOperationException {
    return instantiate(Config.class.getDeclaredConstructor(ConfigProvider.class), configProvider);
  }

  private static Config instantiate(Constructor<Config> constructor, Object... args)
      throws ReflectiveOperationException {
    constructor.setAccessible(true);
    try {
      return constructor.newInstance(args);
    } catch (InvocationTargetException e) {
      throw rethrowCause(e);
    }
  }

  /** Rethrows the exception raised by a reflectively invoked member, unwrapped when unchecked. */
  private static ReflectiveOperationException rethrowCause(InvocationTargetException e) {
    Throwable cause = e.getCause();
    if (cause instanceof RuntimeException) {
      throw (RuntimeException) cause;
    }
    if (cause instanceof Error) {
      throw (Error) cause;
    }
    return e;
  }

  /**
   * Sets an environment variable without rebuilding the global config. {@link WithConfigExtension}
   * clears it after each test.
   */
  private static void setEnv(String name, String value) {
    ((TestEnvironmentVariables) EnvironmentVariables.provider).set(name, value);
  }

  /** Bit set with every value from {@code from} to {@code toInclusive} set. */
  private static BitSet toBitSet(int from, int toInclusive) {
    BitSet bitSet = new BitSet();
    bitSet.set(from, toInclusive + 1);
    return bitSet;
  }

  private static BitSet toBitSet(Collection<Integer> values) {
    BitSet bitSet = new BitSet();
    for (Integer value : values) {
      bitSet.set(value);
    }
    return bitSet;
  }

  /** Runs every {@link ConfigTest} test again with the lenient strictness required by fakes. */
  @Nested
  static class ConfigTestWithFakes extends ConfigTest {
    private StrictnessPolicy strictness;

    @BeforeEach
    void useTestStrictness() {
      strictness = ConfigHelper.get().configInversionStrictFlag();
      ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.TEST);
    }

    @AfterEach
    void restoreStrictness() {
      ConfigHelper.get().setConfigInversionStrict(strictness);
    }

    // spotless:off
    @TableTest({
      "scenario      | name          | enabled",
      "empty         | ''            | true",
      "invalid       | invalid       | true",
      "test-prop     | test-prop     | true",
      "Test-Prop     | Test-Prop     | true",
      "test-env      | test-env      | true",
      "Test-Env      | Test-Env      | true",
      "test          | test          | false",
      "TEST          | TEST          | false",
      "disabled-prop | disabled-prop | false",
      "Disabled-Prop | Disabled-Prop | false",
      "disabled-env  | disabled-env  | false",
      "Disabled-Env  | Disabled-Env  | false"
    })
    // spotless:on
    void verifyRuleConfig(String name, boolean enabled) {
      setEnv("DD_TRACE_TEST_ENABLED", "true");
      setEnv("DD_TRACE_TEST_ENV_ENABLED", "true");
      setEnv("DD_TRACE_DISABLED_ENV_ENABLED", "false");

      System.setProperty("dd.trace.test.enabled", "false");
      System.setProperty("dd.trace.test-prop.enabled", "true");
      System.setProperty("dd.trace.disabled-prop.enabled", "false");

      assertEquals(enabled, Config.get().isRuleEnabled(name));
    }

    // spotless:off
    @TableTest({
      "scenario                               | names                          | defaultEnabled | expected",
      "no names, default enabled              | []                             | true           | true",
      "no names, default disabled             | []                             | false          | false",
      "invalid, default enabled               | [invalid]                      | true           | true",
      "invalid, default disabled              | [invalid]                      | false          | false",
      "test-prop                              | [test-prop]                    | false          | true",
      "test-env                               | [test-env]                     | false          | true",
      "disabled-prop                          | [disabled-prop]                | true           | false",
      "disabled-env                           | [disabled-env]                 | true           | false",
      "other and test-prop                    | [other, test-prop]             | false          | true",
      "other and test-env                     | [other, test-env]              | false          | true",
      "order                                  | [order]                        | false          | true",
      "test-prop and disabled-prop, disabled  | [test-prop, disabled-prop]     | false          | true",
      "disabled-env and test-env, disabled    | [disabled-env, test-env]       | false          | true",
      "test-prop and disabled-prop, enabled   | [test-prop, disabled-prop]     | true           | false",
      "disabled-env and test-env, enabled     | [disabled-env, test-env]       | true           | false"
    })
    // spotless:on
    void verifyIntegrationJmxfetchConfig(
        List<String> names, boolean defaultEnabled, boolean expected) {
      setEnv("DD_JMXFETCH_ORDER_ENABLED", "false");
      setEnv("DD_JMXFETCH_TEST_ENV_ENABLED", "true");
      setEnv("DD_JMXFETCH_DISABLED_ENV_ENABLED", "false");

      System.setProperty("dd.jmxfetch.order.enabled", "true");
      System.setProperty("dd.jmxfetch.test-prop.enabled", "true");
      System.setProperty("dd.jmxfetch.disabled-prop.enabled", "false");

      TreeSet<String> integrationNames = new TreeSet<>(names);
      assertEquals(
          expected, Config.get().isJmxFetchIntegrationEnabled(integrationNames, defaultEnabled));
    }

    // spotless:off
    @TableTest({
      "scenario                               | names                           | defaultEnabled | expected",
      "no names, default enabled              | []                              | true           | true",
      "no names, default disabled             | []                              | false          | false",
      "invalid, default enabled               | [invalid]                       | true           | true",
      "invalid, default disabled              | [invalid]                       | false          | false",
      "test-prop                              | [test-prop]                     | false          | true",
      "test-env                               | [test-env]                      | false          | true",
      "disabled-prop                          | [disabled-prop]                 | true           | false",
      "disabled-env                           | [disabled-env]                  | true           | false",
      "other and test-prop                    | [other, test-prop]              | false          | true",
      "other and test-env                     | [other, test-env]               | false          | true",
      "order                                  | [order]                         | false          | true",
      "test-prop and disabled-prop, disabled  | [test-prop, disabled-prop]      | false          | true",
      "disabled-env and test-env, disabled    | [disabled-env, test-env]        | false          | true",
      "test-prop and disabled-prop, enabled   | [test-prop, disabled-prop]      | true           | false",
      "disabled-env and test-env, enabled     | [disabled-env, test-env]        | true           | false",
      "alias-prop and disabled-prop, disabled | [alias-prop, disabled-prop]     | false          | true",
      "disabled-env and alias-env, disabled   | [disabled-env, alias-env]       | false          | true",
      "alias-prop and disabled-prop, enabled  | [alias-prop, disabled-prop]     | true           | false",
      "disabled-env and alias-env, enabled    | [disabled-env, alias-env]       | true           | false"
    })
    // spotless:on
    void verifyIntegrationTraceAnalyticsConfig(
        List<String> names, boolean defaultEnabled, boolean expected) {
      setEnv("DD_ORDER_ANALYTICS_ENABLED", "false");
      setEnv("DD_TEST_ENV_ANALYTICS_ENABLED", "true");
      setEnv("DD_DISABLED_ENV_ANALYTICS_ENABLED", "false");
      // trace prefix form should take precedence over the old non-prefix form
      setEnv("DD_ALIAS_ENV_ANALYTICS_ENABLED", "false");
      setEnv("DD_TRACE_ALIAS_ENV_ANALYTICS_ENABLED", "true");

      System.setProperty("dd.order.analytics.enabled", "true");
      System.setProperty("dd.test-prop.analytics.enabled", "true");
      System.setProperty("dd.disabled-prop.analytics.enabled", "false");
      // trace prefix form should take precedence over the old non-prefix form
      System.setProperty("dd.alias-prop.analytics.enabled", "false");
      System.setProperty("dd.trace.alias-prop.analytics.enabled", "true");

      TreeSet<String> integrationNames = new TreeSet<>(names);
      assertEquals(
          expected,
          Config.get().isTraceAnalyticsIntegrationEnabled(integrationNames, defaultEnabled));
    }

    // spotless:off
    @TableTest({
      "scenario        | name            | expected",
      "env.zero.test   | env.zero.test   | 0.0",
      "prop.zero.test  | prop.zero.test  | 0",
      "env.float.test  | env.float.test  | 1.0",
      "prop.float.test | prop.float.test | 0.3",
      "float.test      | float.test      | 0.4",
      "negative.test   | negative.test   | -1.0",
      "garbage.test    | garbage.test    | 10.0",
      "default.test    | default.test    | 10.0"
    })
    // spotless:on
    void testGetFloatSettingFromEnvironment(String name, float expected) {
      setEnv("DD_ENV_ZERO_TEST", "0.0");
      setEnv("DD_ENV_FLOAT_TEST", "1.0");
      setEnv("DD_FLOAT_TEST", "0.2");

      System.setProperty("dd.prop.zero.test", "0");
      System.setProperty("dd.prop.float.test", "0.3");
      System.setProperty("dd.float.test", "0.4");
      System.setProperty("dd.garbage.test", "garbage");
      System.setProperty("dd.negative.test", "-1");

      float defaultValue = 10.0f;
      assertEquals(expected, Config.get().configProvider().getFloat(name, defaultValue));
    }

    // spotless:off
    @TableTest({
      "scenario        | name            | expected",
      "env.zero.test   | env.zero.test   | 0.0",
      "prop.zero.test  | prop.zero.test  | 0",
      "env.float.test  | env.float.test  | 1.0",
      "prop.float.test | prop.float.test | 0.3",
      "float.test      | float.test      | 0.4",
      "negative.test   | negative.test   | -1.0",
      "garbage.test    | garbage.test    | 10.0",
      "default.test    | default.test    | 10.0"
    })
    // spotless:on
    void testGetDoubleSettingFromEnvironment(String name, double expected) {
      setEnv("DD_ENV_ZERO_TEST", "0.0");
      setEnv("DD_ENV_FLOAT_TEST", "1.0");
      setEnv("DD_FLOAT_TEST", "0.2");

      System.setProperty("dd.prop.zero.test", "0");
      System.setProperty("dd.prop.float.test", "0.3");
      System.setProperty("dd.float.test", "0.4");
      System.setProperty("dd.garbage.test", "garbage");
      System.setProperty("dd.negative.test", "-1");

      double defaultValue = 10.0;
      assertEquals(expected, Config.get().configProvider().getDouble(name, defaultValue));
    }

    // spotless:off
    @TableTest({
      "scenario              | services                | expected",
      "foo                   | [foo]                   | 0.5",
      "baz                   | [baz]                   | 0.7",
      "doesnotexist          | [doesnotexist]          | 1.0",
      "doesnotexist then foo | [doesnotexist, foo]     | 0.5",
      "doesnotexist then baz | [doesnotexist, baz]     | 0.7",
      "foo then bar          | [foo, bar]              | 0.5",
      "bar then foo          | [bar, foo]              | 0.9",
      "baz then buzz         | [baz, buzz]             | 0.7",
      "buzz then baz         | [buzz, baz]             | 0.3",
      "foo then baz          | [foo, baz]              | 0.5",
      "baz then foo          | [baz, foo]              | 0.7",
      "alias-env then baz    | [alias-env, baz]        | 0.4",
      "alias-prop then foo   | [alias-prop, foo]       | 0.2"
    })
    // spotless:on
    void getAnalyticsSampleRate(List<String> services, float expected) {
      setEnv("DD_FOO_ANALYTICS_SAMPLE_RATE", "0.5");
      setEnv("DD_BAR_ANALYTICS_SAMPLE_RATE", "0.9");
      // trace prefix form should take precedence over the old non-prefix form
      setEnv("DD_ALIAS_ENV_ANALYTICS_SAMPLE_RATE", "0.8");
      setEnv("DD_TRACE_ALIAS_ENV_ANALYTICS_SAMPLE_RATE", "0.4");

      System.setProperty("dd.baz.analytics.sample-rate", "0.7");
      System.setProperty("dd.buzz.analytics.sample-rate", "0.3");
      // trace prefix form should take precedence over the old non-prefix form
      System.setProperty("dd.alias-prop.analytics.sample-rate", "0.1");
      System.setProperty("dd.trace.alias-prop.analytics.sample-rate", "0.2");

      String[] array = services.toArray(new String[0]);
      float value = Config.get().getInstrumentationAnalyticsSampleRate(array);

      assertEquals(expected, value);
    }

    // Static methods test:
    @Test
    void configProviderGetUnitTest() {
      Properties properties = new Properties();
      properties.setProperty("i", "13");
      properties.setProperty("f", "42.42");
      ConfigProvider configProvider = withPropertiesOverride(properties);

      assertEquals(13, configProvider.getDouble("i", 40));
      assertEquals(42.42, configProvider.getDouble("f", 41));
      assertEquals(13, configProvider.getFloat("i", 40));
      assertEquals(42.42f, configProvider.getFloat("f", 41));
      assertEquals(61, configProvider.getInteger("b", 61));
      assertEquals(13, configProvider.getInteger("i", 61));
      assertTrue(configProvider.getBoolean("a", true));
    }
  }
}
