package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_WRITER_TYPE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_REPORTING_INBAND;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_TRACE_RATE_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_WAF_METRICS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_WAF_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_AGENTLESS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CLIENT_IP_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CLOCK_SYNC_PERIOD;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CWS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CWS_TLS_REFRESH;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DATA_STREAMS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_CLASSFILE_DUMP_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_DIAGNOSTICS_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_INSTRUMENT_THE_WORLD;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_POLL_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_UPLOAD_BATCH_SIZE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_UPLOAD_FLUSH_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_UPLOAD_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_VERIFY_BYTECODE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DOGSTATSD_START_DELAY;
import static datadog.trace.api.ConfigDefaults.DEFAULT_GRPC_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_GRPC_SERVER_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HEALTH_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_DEBUG_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_DEDUPLICATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_MAX_CONCURRENT_REQUESTS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_REQUEST_SAMPLING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_VULNERABILITIES_PER_REQUEST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_WEAK_HASH_ALGORITHMS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PERF_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITY_SAMPLING_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITY_SAMPLING_FORCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_INITIAL_POLL_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_TARGETS_KEY;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_TARGETS_KEY_ID;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SCOPE_DEPTH_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SCOPE_ITERATION_KEEP_ALIVE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SECURE_RANDOM;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SITE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_DEPENDENCY_COLLECTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_V05_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANALYTICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_RATE_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_REPORT_HOSTNAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_RESOLVER_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH;
import static datadog.trace.api.DDTags.HOST_TAG;
import static datadog.trace.api.DDTags.INTERNAL_HOST_NAME;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.PID_TAG;
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;
import static datadog.trace.api.DDTags.RUNTIME_VERSION_TAG;
import static datadog.trace.api.DDTags.SERVICE;
import static datadog.trace.api.DDTags.SERVICE_TAG;
import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_HTML;
import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_JSON;
import static datadog.trace.api.config.AppSecConfig.APPSEC_IP_ADDR_HEADER;
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP;
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP;
import static datadog.trace.api.config.AppSecConfig.APPSEC_REPORTING_INBAND;
import static datadog.trace.api.config.AppSecConfig.APPSEC_REPORT_TIMEOUT_SEC;
import static datadog.trace.api.config.AppSecConfig.APPSEC_RULES_FILE;
import static datadog.trace.api.config.AppSecConfig.APPSEC_TRACE_RATE_LIMIT;
import static datadog.trace.api.config.AppSecConfig.APPSEC_WAF_METRICS;
import static datadog.trace.api.config.AppSecConfig.APPSEC_WAF_TIMEOUT;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_AGENTLESS;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_AGENTLESS_DEFAULT;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_TAGS;
import static datadog.trace.api.config.CwsConfig.CWS_ENABLED;
import static datadog.trace.api.config.CwsConfig.CWS_TLS_REFRESH;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_CLASSFILE_DUMP_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_DIAGNOSTICS_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_EXCLUDE_FILE;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_INSTRUMENT_THE_WORLD;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_METRICS_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_POLL_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_PROBE_FILE_LOCATION;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_UPLOAD_BATCH_SIZE;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_UPLOAD_FLUSH_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_VERIFY_BYTECODE;
import static datadog.trace.api.config.GeneralConfig.API_KEY;
import static datadog.trace.api.config.GeneralConfig.API_KEY_FILE;
import static datadog.trace.api.config.GeneralConfig.AZURE_APP_SERVICES;
import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_ARGS;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_HOST;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_NAMED_PIPE;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_PATH;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_PORT;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_START_DELAY;
import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.GLOBAL_TAGS;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_HOST;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_PORT;
import static datadog.trace.api.config.GeneralConfig.PERF_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.PRIMARY_TAG;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_ID_ENABLED;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.SITE;
import static datadog.trace.api.config.GeneralConfig.TAGS;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_DEPENDENCY_COLLECTION_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_BUFFERING_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_IGNORED_RESOURCES;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_MAX_AGGREGATES;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_MAX_PENDING;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_DEDUPLICATION_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_MAX_CONCURRENT_REQUESTS;
import static datadog.trace.api.config.IastConfig.IAST_REQUEST_SAMPLING;
import static datadog.trace.api.config.IastConfig.IAST_VULNERABILITIES_PER_REQUEST;
import static datadog.trace.api.config.IastConfig.IAST_WEAK_CIPHER_ALGORITHMS;
import static datadog.trace.api.config.IastConfig.IAST_WEAK_HASH_ALGORITHMS;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CHECK_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CONFIG;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CONFIG_DIR;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_ENABLED;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_INITIAL_REFRESH_BEANS_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_METRICS_CONFIGS;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_REFRESH_BEANS_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_START_DELAY;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_HOST;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_PORT;
import static datadog.trace.api.config.JmxFetchConfig.JMX_TAGS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AGENTLESS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AGENTLESS_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_VERY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_VERY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCLUDE_AGENT_THREADS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_HOST;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PASSWORD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PORT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PORT_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_USERNAME;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_DELAY;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_DELAY_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TAGS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_COMPRESSION;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_COMPRESSION_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_PERIOD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_PERIOD_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_SUMMARY_ON_413;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_SUMMARY_ON_413_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_TIMEOUT_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_URL;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_ENABLED;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_INITIAL_POLL_INTERVAL;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_TARGETS_KEY;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_TARGETS_KEY_ID;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_URL;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_IGNORED_INBOUND_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_IGNORED_OUTBOUND_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_SERVER_ERROR_STATUSES;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_SERVER_TRIM_PACKAGE_RESOURCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_RESOURCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_ROUTE_BASED_NAMING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HYSTRIX_MEASURED_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.HYSTRIX_TAGS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.IGNITE_CACHE_INCLUDE_KEYS;
import static datadog.trace.api.config.TraceInstrumentationConfig.INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES;
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS;
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_BASE64_DECODING_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS;
import static datadog.trace.api.config.TraceInstrumentationConfig.MESSAGE_BROKER_SPLIT_BY_DESTINATION;
import static datadog.trace.api.config.TraceInstrumentationConfig.OBFUSCATION_QUERY_STRING_REGEXP;
import static datadog.trace.api.config.TraceInstrumentationConfig.PLAY_REPORT_HTTP_STATUS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_INCLUDE_ROUTINGKEY_IN_RESOURCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_EXCHANGES;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_QUEUES;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_ASYNC_TIMEOUT_ERROR;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_PRINCIPAL_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_ROOT_CONTEXT_SERVICE_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.SPRING_DATA_REPOSITORY_INTERFACE_RESOURCE_NAME;
import static datadog.trace.api.config.TracerConfig.AGENT_HOST;
import static datadog.trace.api.config.TracerConfig.AGENT_NAMED_PIPE;
import static datadog.trace.api.config.TracerConfig.AGENT_PORT_LEGACY;
import static datadog.trace.api.config.TracerConfig.AGENT_TIMEOUT;
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET;
import static datadog.trace.api.config.TracerConfig.BAGGAGE_MAPPING;
import static datadog.trace.api.config.TracerConfig.CLIENT_IP_ENABLED;
import static datadog.trace.api.config.TracerConfig.CLOCK_SYNC_PERIOD;
import static datadog.trace.api.config.TracerConfig.ENABLE_TRACE_AGENT_V05;
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.ID_GENERATION_STRATEGY;
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING_FORCE;
import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.config.TracerConfig.PROPAGATION_STYLE_EXTRACT;
import static datadog.trace.api.config.TracerConfig.PROPAGATION_STYLE_INJECT;
import static datadog.trace.api.config.TracerConfig.PROXY_NO_PROXY;
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.RESPONSE_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.SCOPE_DEPTH_LIMIT;
import static datadog.trace.api.config.TracerConfig.SCOPE_INHERIT_ASYNC_PROPAGATION;
import static datadog.trace.api.config.TracerConfig.SCOPE_ITERATION_KEEP_ALIVE;
import static datadog.trace.api.config.TracerConfig.SCOPE_STRICT_MODE;
import static datadog.trace.api.config.TracerConfig.SECURE_RANDOM;
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING;
import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES;
import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES_FILE;
import static datadog.trace.api.config.TracerConfig.SPAN_TAGS;
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_ARGS;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PATH;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PORT;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_URL;
import static datadog.trace.api.config.TracerConfig.TRACE_ANALYTICS_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_HEADER;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_STYLE;
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_STYLE_EXTRACT;
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_STYLE_INJECT;
import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_REPORT_HOSTNAME;
import static datadog.trace.api.config.TracerConfig.TRACE_RESOLVER_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_OPERATION_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_STRICT_WRITES_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_X_DATADOG_TAGS_MAX_LENGTH;
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;
import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;
import static datadog.trace.util.Strings.toEnvVar;

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.config.provider.CapturedEnvironmentConfigSource;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.config.provider.SystemPropertiesConfigSource;
import datadog.trace.util.PidHelper;
import datadog.trace.util.Strings;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config reads values with the following priority: 1) system properties, 2) environment variables,
 * 3) optional configuration file, 4) platform dependant properties. It also includes default values
 * to ensure a valid config.
 *
 * <p>
 *
 * <p>System properties are {@link Config#PREFIX}'ed. Environment variables are the same as the
 * system property, but uppercased and '.' is replaced with '_'.
 */
@Deprecated
public class Config {

  private static final Logger log = LoggerFactory.getLogger(Config.class);

  private final InstrumenterConfig instrumenterConfig;

  private final long startTimeMillis = System.currentTimeMillis();

  /**
   * this is a random UUID that gets generated on JVM start up and is attached to every root span
   * and every JMX metric that is sent out.
   */
  static class RuntimeIdHolder {
    static final String runtimeId = UUID.randomUUID().toString();
  }

  static class HostNameHolder {
    static final String hostName = initHostName();
  }

  private final boolean runtimeIdEnabled;

  /** This is the version of the runtime, ex: 1.8.0_332, 11.0.15, 17.0.3 */
  private final String runtimeVersion;

  /**
   * Note: this has effect only on profiling site. Traces are sent to Datadog agent and are not
   * affected by this setting.
   */
  private final String apiKey;
  /**
   * Note: this has effect only on profiling site. Traces are sent to Datadog agent and are not
   * affected by this setting.
   */
  private final String site;

  private final String serviceName;
  private final boolean serviceNameSetByUser;
  private final String rootContextServiceName;
  private final boolean integrationSynapseLegacyOperationName;
  private final String writerType;
  private final boolean agentConfiguredUsingDefault;
  private final String agentUrl;
  private final String agentHost;
  private final int agentPort;
  private final String agentUnixDomainSocket;
  private final String agentNamedPipe;
  private final int agentTimeout;
  private final Set<String> noProxyHosts;
  private final boolean prioritySamplingEnabled;
  private final String prioritySamplingForce;
  private final boolean traceResolverEnabled;
  private final Map<String, String> serviceMapping;
  private final Map<String, String> tags;
  private final Map<String, String> spanTags;
  private final Map<String, String> jmxTags;
  private final Map<String, String> requestHeaderTags;
  private final Map<String, String> responseHeaderTags;
  private final Map<String, String> baggageMapping;
  private final BitSet httpServerErrorStatuses;
  private final BitSet httpClientErrorStatuses;
  private final boolean httpServerTagQueryString;
  private final boolean httpServerRawQueryString;
  private final boolean httpServerRawResource;
  private final boolean httpServerRouteBasedNaming;
  private final Map<String, String> httpServerPathResourceNameMapping;
  private final boolean httpClientTagQueryString;
  private final boolean httpClientSplitByDomain;
  private final boolean dbClientSplitByInstance;
  private final boolean dbClientSplitByInstanceTypeSuffix;
  private final Set<String> splitByTags;
  private final int scopeDepthLimit;
  private final boolean scopeStrictMode;
  private final boolean scopeInheritAsyncPropagation;
  private final int scopeIterationKeepAlive;
  private final int partialFlushMinSpans;
  private final boolean traceStrictWritesEnabled;
  private final boolean logExtractHeaderNames;
  private final Set<PropagationStyle> propagationStylesToExtract;
  private final Set<PropagationStyle> propagationStylesToInject;
  private final Set<TracePropagationStyle> tracePropagationStylesToExtract;
  private final Set<TracePropagationStyle> tracePropagationStylesToInject;
  private final int clockSyncPeriod;

  private final String dogStatsDNamedPipe;
  private final int dogStatsDStartDelay;

  private final boolean jmxFetchEnabled;
  private final String jmxFetchConfigDir;
  private final List<String> jmxFetchConfigs;
  @Deprecated private final List<String> jmxFetchMetricsConfigs;
  private final Integer jmxFetchCheckPeriod;
  private final Integer jmxFetchInitialRefreshBeansPeriod;
  private final Integer jmxFetchRefreshBeansPeriod;
  private final String jmxFetchStatsdHost;
  private final Integer jmxFetchStatsdPort;
  private final boolean jmxFetchMultipleRuntimeServicesEnabled;
  private final int jmxFetchMultipleRuntimeServicesLimit;

  // These values are default-ed to those of jmx fetch values as needed
  private final boolean healthMetricsEnabled;
  private final String healthMetricsStatsdHost;
  private final Integer healthMetricsStatsdPort;
  private final boolean perfMetricsEnabled;

  private final boolean tracerMetricsEnabled;
  private final boolean tracerMetricsBufferingEnabled;
  private final int tracerMetricsMaxAggregates;
  private final int tracerMetricsMaxPending;

  private final boolean reportHostName;

  private final boolean traceAnalyticsEnabled;
  private final String traceClientIpHeader;
  private final boolean traceClientIpResolverEnabled;

  private final Map<String, String> traceSamplingServiceRules;
  private final Map<String, String> traceSamplingOperationRules;
  private final String traceSamplingRules;
  private final Double traceSampleRate;
  private final int traceRateLimit;
  private final String spanSamplingRules;
  private final String spanSamplingRulesFile;

  private final boolean profilingAgentless;
  private final boolean isDatadogProfilerEnabled;
  @Deprecated private final String profilingUrl;
  private final Map<String, String> profilingTags;
  private final int profilingStartDelay;
  private final boolean profilingStartForceFirst;
  private final int profilingUploadPeriod;
  private final String profilingTemplateOverrideFile;
  private final int profilingUploadTimeout;
  private final String profilingUploadCompression;
  private final String profilingProxyHost;
  private final int profilingProxyPort;
  private final String profilingProxyUsername;
  private final String profilingProxyPassword;
  private final int profilingExceptionSampleLimit;
  private final int profilingDirectAllocationSampleLimit;
  private final int profilingExceptionHistogramTopItems;
  private final int profilingExceptionHistogramMaxCollectionSize;
  private final boolean profilingExcludeAgentThreads;
  private final boolean profilingUploadSummaryOn413Enabled;

  private final boolean crashTrackingAgentless;
  private final Map<String, String> crashTrackingTags;

  private final boolean clientIpEnabled;

  private final boolean appSecReportingInband;
  private final String appSecRulesFile;
  private final int appSecReportMinTimeout;
  private final int appSecReportMaxTimeout;
  private final int appSecTraceRateLimit;
  private final boolean appSecWafMetrics;
  private final int appSecWafTimeout;
  private final String appSecObfuscationParameterKeyRegexp;
  private final String appSecObfuscationParameterValueRegexp;
  private final String appSecHttpBlockedTemplateHtml;
  private final String appSecHttpBlockedTemplateJson;

  private final int iastMaxConcurrentRequests;
  private final int iastVulnerabilitiesPerRequest;
  private final float iastRequestSampling;
  private final boolean iastDebugEnabled;

  private final boolean ciVisibilityAgentlessEnabled;
  private final String ciVisibilityAgentlessUrl;

  private final boolean remoteConfigEnabled;
  private final boolean remoteConfigIntegrityCheckEnabled;
  private final String remoteConfigUrl;
  private final int remoteConfigInitialPollInterval;
  private final long remoteConfigMaxPayloadSize;
  private final String remoteConfigTargetsKeyId;
  private final String remoteConfigTargetsKey;

  private final boolean debuggerEnabled;
  private final int debuggerUploadTimeout;
  private final int debuggerUploadFlushInterval;
  private final boolean debuggerClassFileDumpEnabled;
  private final int debuggerPollInterval;
  private final int debuggerDiagnosticsInterval;
  private final boolean debuggerMetricEnabled;
  private final String debuggerProbeFileLocation;
  private final int debuggerUploadBatchSize;
  private final long debuggerMaxPayloadSize;
  private final boolean debuggerVerifyByteCode;
  private final boolean debuggerInstrumentTheWorld;
  private final String debuggerExcludeFile;

  private final boolean awsPropagationEnabled;
  private final boolean sqsPropagationEnabled;

  private final boolean kafkaClientPropagationEnabled;
  private final Set<String> kafkaClientPropagationDisabledTopics;
  private final boolean kafkaClientBase64DecodingEnabled;

  private final boolean jmsPropagationEnabled;
  private final Set<String> jmsPropagationDisabledTopics;
  private final Set<String> jmsPropagationDisabledQueues;

  private final boolean rabbitPropagationEnabled;
  private final Set<String> rabbitPropagationDisabledQueues;
  private final Set<String> rabbitPropagationDisabledExchanges;

  private final boolean rabbitIncludeRoutingKeyInResource;

  private final boolean messageBrokerSplitByDestination;

  private final boolean hystrixTagsEnabled;
  private final boolean hystrixMeasuredEnabled;

  private final boolean igniteCacheIncludeKeys;

  private final String obfuscationQueryRegexp;

  // TODO: remove at a future point.
  private final boolean playReportHttpStatus;

  private final boolean servletPrincipalEnabled;
  private final boolean servletAsyncTimeoutError;

  private final boolean springDataRepositoryInterfaceResourceName;

  private final int xDatadogTagsMaxLength;

  private final boolean traceAgentV05Enabled;

  private final boolean debugEnabled;
  private final String configFileStatus;

  private final IdGenerationStrategy idGenerationStrategy;

  private final boolean secureRandom;

  private final Set<String> grpcIgnoredInboundMethods;
  private final Set<String> grpcIgnoredOutboundMethods;
  private final boolean grpcServerTrimPackageResource;
  private final BitSet grpcServerErrorStatuses;
  private final BitSet grpcClientErrorStatuses;

  private final boolean cwsEnabled;
  private final int cwsTlsRefresh;

  private final boolean dataStreamsEnabled;

  private final Set<String> iastWeakHashAlgorithms;

  private final Pattern iastWeakCipherAlgorithms;

  private final boolean iastDeduplicationEnabled;

  private final int telemetryHeartbeatInterval;
  private final boolean isTelemetryDependencyServiceEnabled;

  private final boolean azureAppServices;
  private final String traceAgentPath;
  private final List<String> traceAgentArgs;
  private final String dogStatsDPath;
  private final List<String> dogStatsDArgs;

  private String env;
  private String version;
  private final String primaryTag;

  private final ConfigProvider configProvider;

  // Read order: System Properties -> Env Variables, [-> properties file], [-> default value]
  private Config() {
    this(ConfigProvider.createDefault());
  }

  private Config(final ConfigProvider configProvider) {
    this(configProvider, new InstrumenterConfig(configProvider));
  }

  private Config(final ConfigProvider configProvider, final InstrumenterConfig instrumenterConfig) {
    this.configProvider = configProvider;
    this.instrumenterConfig = instrumenterConfig;
    configFileStatus = configProvider.getConfigFileStatus();
    runtimeIdEnabled = configProvider.getBoolean(RUNTIME_ID_ENABLED, true);
    runtimeVersion = System.getProperty("java.version", "unknown");

    // Note: We do not want APiKey to be loaded from property for security reasons
    // Note: we do not use defined default here
    // FIXME: We should use better authentication mechanism
    final String apiKeyFile = configProvider.getString(API_KEY_FILE);
    String tmpApiKey =
        configProvider.getStringExcludingSource(API_KEY, null, SystemPropertiesConfigSource.class);
    if (apiKeyFile != null) {
      try {
        tmpApiKey =
            new String(Files.readAllBytes(Paths.get(apiKeyFile)), StandardCharsets.UTF_8).trim();
      } catch (final IOException e) {
        log.error("Cannot read API key from file {}, skipping", apiKeyFile, e);
      }
    }
    site = configProvider.getString(SITE, DEFAULT_SITE);

    String userProvidedServiceName =
        configProvider.getStringExcludingSource(
            SERVICE, null, CapturedEnvironmentConfigSource.class, SERVICE_NAME);

    if (userProvidedServiceName == null) {
      serviceNameSetByUser = false;
      serviceName = configProvider.getString(SERVICE, DEFAULT_SERVICE_NAME, SERVICE_NAME);
    } else {
      serviceNameSetByUser = true;
      serviceName = userProvidedServiceName;
    }

    rootContextServiceName =
        configProvider.getString(
            SERVLET_ROOT_CONTEXT_SERVICE_NAME, DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME);

    integrationSynapseLegacyOperationName =
        configProvider.getBoolean(INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME, false);
    writerType = configProvider.getString(WRITER_TYPE, DEFAULT_AGENT_WRITER_TYPE);

    String lambdaInitType = getEnv("AWS_LAMBDA_INITIALIZATION_TYPE");
    if (lambdaInitType != null && lambdaInitType.equals("snap-start")) {
      secureRandom = true;
    } else {
      secureRandom = configProvider.getBoolean(SECURE_RANDOM, DEFAULT_SECURE_RANDOM);
    }

    String strategyName = configProvider.getString(ID_GENERATION_STRATEGY);
    if (secureRandom) {
      strategyName = "SECURE_RANDOM";
    }
    if (strategyName == null) {
      strategyName = "RANDOM";
    }
    IdGenerationStrategy strategy = IdGenerationStrategy.fromName(strategyName);
    if (strategy == null) {
      log.warn(
          "*** you are trying to use an unknown id generation strategy {} - falling back to RANDOM",
          strategyName);
      strategyName = "RANDOM";
      strategy = IdGenerationStrategy.fromName(strategyName);
    }
    if (!strategyName.equals("RANDOM") && !strategyName.equals("SECURE_RANDOM")) {
      log.warn(
          "*** you are using an unsupported id generation strategy {} - this can impact correctness of traces",
          strategyName);
    }
    idGenerationStrategy = strategy;

    String agentHostFromEnvironment = null;
    int agentPortFromEnvironment = -1;
    String unixSocketFromEnvironment = null;
    boolean rebuildAgentUrl = false;

    final String agentUrlFromEnvironment = configProvider.getString(TRACE_AGENT_URL);
    if (agentUrlFromEnvironment != null) {
      try {
        final URI parsedAgentUrl = new URI(agentUrlFromEnvironment);
        agentHostFromEnvironment = parsedAgentUrl.getHost();
        agentPortFromEnvironment = parsedAgentUrl.getPort();
        if ("unix".equals(parsedAgentUrl.getScheme())) {
          unixSocketFromEnvironment = parsedAgentUrl.getPath();
        }
      } catch (URISyntaxException e) {
        log.warn("{} not configured correctly: {}. Ignoring", TRACE_AGENT_URL, e.getMessage());
      }
    }

    if (agentHostFromEnvironment == null) {
      agentHostFromEnvironment = configProvider.getString(AGENT_HOST);
      rebuildAgentUrl = true;
    }

    if (agentPortFromEnvironment < 0) {
      agentPortFromEnvironment = configProvider.getInteger(TRACE_AGENT_PORT, -1, AGENT_PORT_LEGACY);
      rebuildAgentUrl = true;
    }

    if (agentHostFromEnvironment == null) {
      agentHost = DEFAULT_AGENT_HOST;
    } else {
      agentHost = agentHostFromEnvironment;
    }

    if (agentPortFromEnvironment < 0) {
      agentPort = DEFAULT_TRACE_AGENT_PORT;
    } else {
      agentPort = agentPortFromEnvironment;
    }

    if (rebuildAgentUrl) {
      agentUrl = "http://" + agentHost + ":" + agentPort;
    } else {
      agentUrl = agentUrlFromEnvironment;
    }

    if (unixSocketFromEnvironment == null) {
      unixSocketFromEnvironment = configProvider.getString(AGENT_UNIX_DOMAIN_SOCKET);
      String unixPrefix = "unix://";
      // handle situation where someone passes us a unix:// URL instead of a socket path
      if (unixSocketFromEnvironment != null && unixSocketFromEnvironment.startsWith(unixPrefix)) {
        unixSocketFromEnvironment = unixSocketFromEnvironment.substring(unixPrefix.length());
      }
    }

    agentUnixDomainSocket = unixSocketFromEnvironment;

    agentNamedPipe = configProvider.getString(AGENT_NAMED_PIPE);

    agentConfiguredUsingDefault =
        agentHostFromEnvironment == null
            && agentPortFromEnvironment < 0
            && unixSocketFromEnvironment == null
            && agentNamedPipe == null;

    agentTimeout = configProvider.getInteger(AGENT_TIMEOUT, DEFAULT_AGENT_TIMEOUT);

    // DD_PROXY_NO_PROXY is specified as a space-separated list of hosts
    noProxyHosts = tryMakeImmutableSet(configProvider.getSpacedList(PROXY_NO_PROXY));

    prioritySamplingEnabled =
        configProvider.getBoolean(PRIORITY_SAMPLING, DEFAULT_PRIORITY_SAMPLING_ENABLED);
    prioritySamplingForce =
        configProvider.getString(PRIORITY_SAMPLING_FORCE, DEFAULT_PRIORITY_SAMPLING_FORCE);

    traceResolverEnabled =
        configProvider.getBoolean(TRACE_RESOLVER_ENABLED, DEFAULT_TRACE_RESOLVER_ENABLED);
    serviceMapping = configProvider.getMergedMap(SERVICE_MAPPING);

    {
      final Map<String, String> tags = new HashMap<>(configProvider.getMergedMap(GLOBAL_TAGS));
      tags.putAll(configProvider.getMergedMap(TAGS));
      this.tags = getMapWithPropertiesDefinedByEnvironment(tags, ENV, VERSION);
    }

    spanTags = configProvider.getMergedMap(SPAN_TAGS);
    jmxTags = configProvider.getMergedMap(JMX_TAGS);

    primaryTag = configProvider.getString(PRIMARY_TAG);

    if (isEnabled(false, HEADER_TAGS, ".legacy.parsing.enabled")) {
      requestHeaderTags = configProvider.getMergedMap(HEADER_TAGS);
      responseHeaderTags = Collections.emptyMap();
      if (configProvider.isSet(REQUEST_HEADER_TAGS)) {
        logIgnoredSettingWarning(REQUEST_HEADER_TAGS, HEADER_TAGS, ".legacy.parsing.enabled");
      }
      if (configProvider.isSet(RESPONSE_HEADER_TAGS)) {
        logIgnoredSettingWarning(RESPONSE_HEADER_TAGS, HEADER_TAGS, ".legacy.parsing.enabled");
      }
    } else {
      requestHeaderTags =
          configProvider.getMergedMapWithOptionalMappings(
              "http.request.headers.", true, HEADER_TAGS, REQUEST_HEADER_TAGS);
      responseHeaderTags =
          configProvider.getMergedMapWithOptionalMappings(
              "http.response.headers.", true, HEADER_TAGS, RESPONSE_HEADER_TAGS);
    }

    baggageMapping = configProvider.getMergedMap(BAGGAGE_MAPPING);

    httpServerPathResourceNameMapping =
        configProvider.getOrderedMap(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING);

    httpServerErrorStatuses =
        configProvider.getIntegerRange(
            HTTP_SERVER_ERROR_STATUSES, DEFAULT_HTTP_SERVER_ERROR_STATUSES);

    httpClientErrorStatuses =
        configProvider.getIntegerRange(
            HTTP_CLIENT_ERROR_STATUSES, DEFAULT_HTTP_CLIENT_ERROR_STATUSES);

    httpServerTagQueryString =
        configProvider.getBoolean(
            HTTP_SERVER_TAG_QUERY_STRING, DEFAULT_HTTP_SERVER_TAG_QUERY_STRING);

    httpServerRawQueryString = configProvider.getBoolean(HTTP_SERVER_RAW_QUERY_STRING, true);

    httpServerRawResource = configProvider.getBoolean(HTTP_SERVER_RAW_RESOURCE, false);

    httpServerRouteBasedNaming =
        configProvider.getBoolean(
            HTTP_SERVER_ROUTE_BASED_NAMING, DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING);

    httpClientTagQueryString =
        configProvider.getBoolean(
            HTTP_CLIENT_TAG_QUERY_STRING, DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING);

    httpClientSplitByDomain =
        configProvider.getBoolean(
            HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN);

    dbClientSplitByInstance =
        configProvider.getBoolean(
            DB_CLIENT_HOST_SPLIT_BY_INSTANCE, DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE);

    dbClientSplitByInstanceTypeSuffix =
        configProvider.getBoolean(
            DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX,
            DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX);

    splitByTags = tryMakeImmutableSet(configProvider.getList(SPLIT_BY_TAGS));

    springDataRepositoryInterfaceResourceName =
        configProvider.getBoolean(SPRING_DATA_REPOSITORY_INTERFACE_RESOURCE_NAME, true);

    scopeDepthLimit = configProvider.getInteger(SCOPE_DEPTH_LIMIT, DEFAULT_SCOPE_DEPTH_LIMIT);

    scopeStrictMode = configProvider.getBoolean(SCOPE_STRICT_MODE, false);

    scopeInheritAsyncPropagation = configProvider.getBoolean(SCOPE_INHERIT_ASYNC_PROPAGATION, true);

    scopeIterationKeepAlive =
        configProvider.getInteger(SCOPE_ITERATION_KEEP_ALIVE, DEFAULT_SCOPE_ITERATION_KEEP_ALIVE);

    partialFlushMinSpans =
        configProvider.getInteger(PARTIAL_FLUSH_MIN_SPANS, DEFAULT_PARTIAL_FLUSH_MIN_SPANS);

    traceStrictWritesEnabled = configProvider.getBoolean(TRACE_STRICT_WRITES_ENABLED, false);

    logExtractHeaderNames =
        configProvider.getBoolean(
            PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED,
            DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED);

    {
      // The dd.propagation.style.(extract|inject) settings have been deprecated in
      // favor of dd.trace.propagation.style(|.extract|.inject) settings.
      // The different propagation settings when set will be applied in the following order of
      // precedence, and warnings will be logged for both deprecation and overrides.
      // * dd.trace.propagation.style.(extract|inject)
      // * dd.trace.propagation.style
      // * dd.propagation.style.(extract|inject)
      Set<PropagationStyle> deprecatedExtract =
          getSettingsSetFromEnvironment(
              PROPAGATION_STYLE_EXTRACT, PropagationStyle::valueOfConfigName);
      Set<PropagationStyle> deprecatedInject =
          getSettingsSetFromEnvironment(
              PROPAGATION_STYLE_INJECT, PropagationStyle::valueOfConfigName);
      Set<TracePropagationStyle> common =
          getSettingsSetFromEnvironment(
              TRACE_PROPAGATION_STYLE, TracePropagationStyle::valueOfDisplayName);
      Set<TracePropagationStyle> extract =
          getSettingsSetFromEnvironment(
              TRACE_PROPAGATION_STYLE_EXTRACT, TracePropagationStyle::valueOfDisplayName);
      Set<TracePropagationStyle> inject =
          getSettingsSetFromEnvironment(
              TRACE_PROPAGATION_STYLE_INJECT, TracePropagationStyle::valueOfDisplayName);
      String extractOrigin = TRACE_PROPAGATION_STYLE_EXTRACT;
      String injectOrigin = TRACE_PROPAGATION_STYLE_INJECT;
      // Check if we should use the common setting for extraction
      if (extract.isEmpty()) {
        extract = common;
        extractOrigin = TRACE_PROPAGATION_STYLE;
      } else if (!common.isEmpty()) {
        // The more specific settings will override the common setting, so log a warning
        logOverriddenSettingWarning(
            TRACE_PROPAGATION_STYLE, TRACE_PROPAGATION_STYLE_EXTRACT, extract);
      }
      // Check if we should use the common setting for injection
      if (inject.isEmpty()) {
        inject = common;
        injectOrigin = TRACE_PROPAGATION_STYLE;
      } else if (!common.isEmpty()) {
        // The more specific settings will override the common setting, so log a warning
        logOverriddenSettingWarning(
            TRACE_PROPAGATION_STYLE, TRACE_PROPAGATION_STYLE_INJECT, inject);
      }
      // Check if we should use the deprecated setting for extraction
      if (extract.isEmpty()) {
        // If we don't have a new setting, we convert the deprecated one
        extract = convertSettingsSet(deprecatedExtract, PropagationStyle::getNewStyles);
        if (!extract.isEmpty()) {
          logDeprecatedConvertedSetting(
              PROPAGATION_STYLE_EXTRACT,
              deprecatedExtract,
              TRACE_PROPAGATION_STYLE_EXTRACT,
              extract);
        }
      } else if (!deprecatedExtract.isEmpty()) {
        // If we have a new setting, we log a warning
        logOverriddenDeprecatedSettingWarning(PROPAGATION_STYLE_EXTRACT, extractOrigin, extract);
      }
      // Check if we should use the deprecated setting for injection
      if (inject.isEmpty()) {
        // If we don't have a new setting, we convert the deprecated one
        inject = convertSettingsSet(deprecatedInject, PropagationStyle::getNewStyles);
        if (!inject.isEmpty()) {
          logDeprecatedConvertedSetting(
              PROPAGATION_STYLE_INJECT, deprecatedInject, TRACE_PROPAGATION_STYLE_INJECT, inject);
        }
      } else if (!deprecatedInject.isEmpty()) {
        // If we have a new setting, we log a warning
        logOverriddenDeprecatedSettingWarning(PROPAGATION_STYLE_INJECT, injectOrigin, inject);
      }
      // Now we can check if we should pick the default injection/extraction
      tracePropagationStylesToExtract =
          extract.isEmpty() ? Collections.singleton(TracePropagationStyle.DATADOG) : extract;
      tracePropagationStylesToInject =
          inject.isEmpty() ? Collections.singleton(TracePropagationStyle.DATADOG) : inject;
      // These setting are here for backwards compatibility until they can be removed in a major
      // release of the tracer
      propagationStylesToExtract =
          deprecatedExtract.isEmpty()
              ? Collections.singleton(PropagationStyle.DATADOG)
              : deprecatedExtract;
      propagationStylesToInject =
          deprecatedInject.isEmpty()
              ? Collections.singleton(PropagationStyle.DATADOG)
              : deprecatedInject;
    }

    clockSyncPeriod = configProvider.getInteger(CLOCK_SYNC_PERIOD, DEFAULT_CLOCK_SYNC_PERIOD);

    dogStatsDNamedPipe = configProvider.getString(DOGSTATSD_NAMED_PIPE);

    dogStatsDStartDelay =
        configProvider.getInteger(
            DOGSTATSD_START_DELAY, DEFAULT_DOGSTATSD_START_DELAY, JMX_FETCH_START_DELAY);

    boolean runtimeMetricsEnabled = configProvider.getBoolean(RUNTIME_METRICS_ENABLED, true);

    jmxFetchEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(JMX_FETCH_ENABLED, DEFAULT_JMX_FETCH_ENABLED);
    jmxFetchConfigDir = configProvider.getString(JMX_FETCH_CONFIG_DIR);
    jmxFetchConfigs = tryMakeImmutableList(configProvider.getList(JMX_FETCH_CONFIG));
    jmxFetchMetricsConfigs =
        tryMakeImmutableList(configProvider.getList(JMX_FETCH_METRICS_CONFIGS));
    jmxFetchCheckPeriod = configProvider.getInteger(JMX_FETCH_CHECK_PERIOD);
    jmxFetchInitialRefreshBeansPeriod =
        configProvider.getInteger(JMX_FETCH_INITIAL_REFRESH_BEANS_PERIOD);
    jmxFetchRefreshBeansPeriod = configProvider.getInteger(JMX_FETCH_REFRESH_BEANS_PERIOD);

    jmxFetchStatsdPort = configProvider.getInteger(JMX_FETCH_STATSD_PORT, DOGSTATSD_PORT);
    jmxFetchStatsdHost =
        configProvider.getString(
            JMX_FETCH_STATSD_HOST,
            // default to agent host if an explicit port has been set
            null != jmxFetchStatsdPort && jmxFetchStatsdPort > 0 ? agentHost : null,
            DOGSTATSD_HOST);

    jmxFetchMultipleRuntimeServicesEnabled =
        configProvider.getBoolean(
            JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED,
            DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED);
    jmxFetchMultipleRuntimeServicesLimit =
        configProvider.getInteger(
            JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT,
            DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT);

    // Writer.Builder createMonitor will use the values of the JMX fetch & agent to fill-in defaults
    healthMetricsEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(HEALTH_METRICS_ENABLED, DEFAULT_HEALTH_METRICS_ENABLED);
    healthMetricsStatsdHost = configProvider.getString(HEALTH_METRICS_STATSD_HOST);
    healthMetricsStatsdPort = configProvider.getInteger(HEALTH_METRICS_STATSD_PORT);
    perfMetricsEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(PERF_METRICS_ENABLED, DEFAULT_PERF_METRICS_ENABLED);

    tracerMetricsEnabled = configProvider.getBoolean(TRACER_METRICS_ENABLED, false);
    tracerMetricsBufferingEnabled =
        configProvider.getBoolean(TRACER_METRICS_BUFFERING_ENABLED, false);
    tracerMetricsMaxAggregates = configProvider.getInteger(TRACER_METRICS_MAX_AGGREGATES, 2048);
    tracerMetricsMaxPending = configProvider.getInteger(TRACER_METRICS_MAX_PENDING, 2048);

    reportHostName =
        configProvider.getBoolean(TRACE_REPORT_HOSTNAME, DEFAULT_TRACE_REPORT_HOSTNAME);

    traceAgentV05Enabled =
        configProvider.getBoolean(ENABLE_TRACE_AGENT_V05, DEFAULT_TRACE_AGENT_V05_ENABLED);

    traceAnalyticsEnabled =
        configProvider.getBoolean(TRACE_ANALYTICS_ENABLED, DEFAULT_TRACE_ANALYTICS_ENABLED);

    String traceClientIpHeader = configProvider.getString(TRACE_CLIENT_IP_HEADER);
    if (traceClientIpHeader == null) {
      traceClientIpHeader = configProvider.getString(APPSEC_IP_ADDR_HEADER);
    }
    if (traceClientIpHeader != null) {
      traceClientIpHeader = traceClientIpHeader.toLowerCase(Locale.ROOT);
    }
    this.traceClientIpHeader = traceClientIpHeader;

    traceClientIpResolverEnabled =
        configProvider.getBoolean(TRACE_CLIENT_IP_RESOLVER_ENABLED, true);

    traceSamplingServiceRules = configProvider.getMergedMap(TRACE_SAMPLING_SERVICE_RULES);
    traceSamplingOperationRules = configProvider.getMergedMap(TRACE_SAMPLING_OPERATION_RULES);
    traceSamplingRules = configProvider.getString(TRACE_SAMPLING_RULES);
    traceSampleRate = configProvider.getDouble(TRACE_SAMPLE_RATE);
    traceRateLimit = configProvider.getInteger(TRACE_RATE_LIMIT, DEFAULT_TRACE_RATE_LIMIT);
    spanSamplingRules = configProvider.getString(SPAN_SAMPLING_RULES);
    spanSamplingRulesFile = configProvider.getString(SPAN_SAMPLING_RULES_FILE);

    profilingAgentless =
        configProvider.getBoolean(PROFILING_AGENTLESS, PROFILING_AGENTLESS_DEFAULT);
    isDatadogProfilerEnabled =
        configProvider.getBoolean(
            PROFILING_DATADOG_PROFILER_ENABLED, isDatadogProfilerSafeInCurrentEnvironment());
    profilingUrl = configProvider.getString(PROFILING_URL);

    if (tmpApiKey == null) {
      final String oldProfilingApiKeyFile = configProvider.getString(PROFILING_API_KEY_FILE_OLD);
      tmpApiKey = getEnv(propertyNameToEnvironmentVariableName(PROFILING_API_KEY_OLD));
      if (oldProfilingApiKeyFile != null) {
        try {
          tmpApiKey =
              new String(
                      Files.readAllBytes(Paths.get(oldProfilingApiKeyFile)), StandardCharsets.UTF_8)
                  .trim();
        } catch (final IOException e) {
          log.error("Cannot read API key from file {}, skipping", oldProfilingApiKeyFile, e);
        }
      }
    }
    if (tmpApiKey == null) {
      final String veryOldProfilingApiKeyFile =
          configProvider.getString(PROFILING_API_KEY_FILE_VERY_OLD);
      tmpApiKey = getEnv(propertyNameToEnvironmentVariableName(PROFILING_API_KEY_VERY_OLD));
      if (veryOldProfilingApiKeyFile != null) {
        try {
          tmpApiKey =
              new String(
                      Files.readAllBytes(Paths.get(veryOldProfilingApiKeyFile)),
                      StandardCharsets.UTF_8)
                  .trim();
        } catch (final IOException e) {
          log.error("Cannot read API key from file {}, skipping", veryOldProfilingApiKeyFile, e);
        }
      }
    }

    profilingTags = configProvider.getMergedMap(PROFILING_TAGS);
    profilingStartDelay =
        configProvider.getInteger(PROFILING_START_DELAY, PROFILING_START_DELAY_DEFAULT);
    profilingStartForceFirst =
        configProvider.getBoolean(PROFILING_START_FORCE_FIRST, PROFILING_START_FORCE_FIRST_DEFAULT);
    profilingUploadPeriod =
        configProvider.getInteger(PROFILING_UPLOAD_PERIOD, PROFILING_UPLOAD_PERIOD_DEFAULT);
    profilingTemplateOverrideFile = configProvider.getString(PROFILING_TEMPLATE_OVERRIDE_FILE);
    profilingUploadTimeout =
        configProvider.getInteger(PROFILING_UPLOAD_TIMEOUT, PROFILING_UPLOAD_TIMEOUT_DEFAULT);
    profilingUploadCompression =
        configProvider.getString(
            PROFILING_UPLOAD_COMPRESSION, PROFILING_UPLOAD_COMPRESSION_DEFAULT);
    profilingProxyHost = configProvider.getString(PROFILING_PROXY_HOST);
    profilingProxyPort =
        configProvider.getInteger(PROFILING_PROXY_PORT, PROFILING_PROXY_PORT_DEFAULT);
    profilingProxyUsername = configProvider.getString(PROFILING_PROXY_USERNAME);
    profilingProxyPassword = configProvider.getString(PROFILING_PROXY_PASSWORD);

    profilingExceptionSampleLimit =
        configProvider.getInteger(
            PROFILING_EXCEPTION_SAMPLE_LIMIT, PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT);
    profilingDirectAllocationSampleLimit =
        configProvider.getInteger(
            PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT,
            PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT_DEFAULT);
    profilingExceptionHistogramTopItems =
        configProvider.getInteger(
            PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS,
            PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT);
    profilingExceptionHistogramMaxCollectionSize =
        configProvider.getInteger(
            PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE,
            PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT);

    profilingExcludeAgentThreads = configProvider.getBoolean(PROFILING_EXCLUDE_AGENT_THREADS, true);

    profilingUploadSummaryOn413Enabled =
        configProvider.getBoolean(
            PROFILING_UPLOAD_SUMMARY_ON_413, PROFILING_UPLOAD_SUMMARY_ON_413_DEFAULT);

    crashTrackingAgentless =
        configProvider.getBoolean(CRASH_TRACKING_AGENTLESS, CRASH_TRACKING_AGENTLESS_DEFAULT);
    crashTrackingTags = configProvider.getMergedMap(CRASH_TRACKING_TAGS);

    int telemetryInterval =
        configProvider.getInteger(
            TELEMETRY_HEARTBEAT_INTERVAL, DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL);
    if (telemetryInterval < 1 || telemetryInterval > 3600) {
      log.warn(
          "Wrong Telemetry heartbeat interval: {}. The value must be in range 1-3600",
          telemetryInterval);
      telemetryInterval = DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL;
    }
    telemetryHeartbeatInterval = telemetryInterval;

    isTelemetryDependencyServiceEnabled =
        configProvider.getBoolean(
            TELEMETRY_DEPENDENCY_COLLECTION_ENABLED,
            DEFAULT_TELEMETRY_DEPENDENCY_COLLECTION_ENABLED);

    clientIpEnabled = configProvider.getBoolean(CLIENT_IP_ENABLED, DEFAULT_CLIENT_IP_ENABLED);

    appSecReportingInband =
        configProvider.getBoolean(APPSEC_REPORTING_INBAND, DEFAULT_APPSEC_REPORTING_INBAND);
    appSecRulesFile = configProvider.getString(APPSEC_RULES_FILE, null);

    // Default AppSec report timeout min=5, max=60
    appSecReportMaxTimeout = configProvider.getInteger(APPSEC_REPORT_TIMEOUT_SEC, 60);
    appSecReportMinTimeout = Math.min(appSecReportMaxTimeout, 5);

    appSecTraceRateLimit =
        configProvider.getInteger(APPSEC_TRACE_RATE_LIMIT, DEFAULT_APPSEC_TRACE_RATE_LIMIT);

    appSecWafMetrics = configProvider.getBoolean(APPSEC_WAF_METRICS, DEFAULT_APPSEC_WAF_METRICS);

    appSecWafTimeout = configProvider.getInteger(APPSEC_WAF_TIMEOUT, DEFAULT_APPSEC_WAF_TIMEOUT);

    appSecObfuscationParameterKeyRegexp =
        configProvider.getString(APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP, null);
    appSecObfuscationParameterValueRegexp =
        configProvider.getString(APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP, null);

    appSecHttpBlockedTemplateHtml =
        configProvider.getString(APPSEC_HTTP_BLOCKED_TEMPLATE_HTML, null);
    appSecHttpBlockedTemplateJson =
        configProvider.getString(APPSEC_HTTP_BLOCKED_TEMPLATE_JSON, null);

    iastDebugEnabled = configProvider.getBoolean(IAST_DEBUG_ENABLED, DEFAULT_IAST_DEBUG_ENABLED);

    iastMaxConcurrentRequests =
        configProvider.getInteger(
            IAST_MAX_CONCURRENT_REQUESTS, DEFAULT_IAST_MAX_CONCURRENT_REQUESTS);
    iastVulnerabilitiesPerRequest =
        configProvider.getInteger(
            IAST_VULNERABILITIES_PER_REQUEST, DEFAULT_IAST_VULNERABILITIES_PER_REQUEST);
    iastRequestSampling =
        configProvider.getFloat(IAST_REQUEST_SAMPLING, DEFAULT_IAST_REQUEST_SAMPLING);
    iastWeakHashAlgorithms =
        tryMakeImmutableSet(
            configProvider.getSet(IAST_WEAK_HASH_ALGORITHMS, DEFAULT_IAST_WEAK_HASH_ALGORITHMS));
    iastWeakCipherAlgorithms =
        getPattern(
            DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS,
            configProvider.getString(IAST_WEAK_CIPHER_ALGORITHMS));
    iastDeduplicationEnabled =
        configProvider.getBoolean(IAST_DEDUPLICATION_ENABLED, DEFAULT_IAST_DEDUPLICATION_ENABLED);

    ciVisibilityAgentlessEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_AGENTLESS_ENABLED, DEFAULT_CIVISIBILITY_AGENTLESS_ENABLED);

    final String ciVisibilityAgentlessUrlStr = configProvider.getString(CIVISIBILITY_AGENTLESS_URL);
    URI parsedCiVisibilityUri = null;
    if (ciVisibilityAgentlessUrlStr != null && !ciVisibilityAgentlessUrlStr.isEmpty()) {
      try {
        parsedCiVisibilityUri = new URL(ciVisibilityAgentlessUrlStr).toURI();
      } catch (MalformedURLException | URISyntaxException ex) {
        log.error(
            "Cannot parse CI Visibility agentless URL '{}', skipping", ciVisibilityAgentlessUrlStr);
      }
    }
    if (parsedCiVisibilityUri != null) {
      ciVisibilityAgentlessUrl = ciVisibilityAgentlessUrlStr;
    } else {
      ciVisibilityAgentlessUrl = null;
    }

    remoteConfigEnabled =
        configProvider.getBoolean(REMOTE_CONFIG_ENABLED, DEFAULT_REMOTE_CONFIG_ENABLED);
    remoteConfigIntegrityCheckEnabled =
        configProvider.getBoolean(
            REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED, DEFAULT_REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED);
    remoteConfigUrl = configProvider.getString(REMOTE_CONFIG_URL);
    remoteConfigInitialPollInterval =
        configProvider.getInteger(
            REMOTE_CONFIG_INITIAL_POLL_INTERVAL, DEFAULT_REMOTE_CONFIG_INITIAL_POLL_INTERVAL);
    remoteConfigMaxPayloadSize =
        configProvider.getInteger(
                REMOTE_CONFIG_MAX_PAYLOAD_SIZE, DEFAULT_REMOTE_CONFIG_MAX_PAYLOAD_SIZE)
            * 1024;
    remoteConfigTargetsKeyId =
        configProvider.getString(
            REMOTE_CONFIG_TARGETS_KEY_ID, DEFAULT_REMOTE_CONFIG_TARGETS_KEY_ID);
    remoteConfigTargetsKey =
        configProvider.getString(REMOTE_CONFIG_TARGETS_KEY, DEFAULT_REMOTE_CONFIG_TARGETS_KEY);

    debuggerEnabled = configProvider.getBoolean(DEBUGGER_ENABLED, DEFAULT_DEBUGGER_ENABLED);
    debuggerUploadTimeout =
        configProvider.getInteger(DEBUGGER_UPLOAD_TIMEOUT, DEFAULT_DEBUGGER_UPLOAD_TIMEOUT);
    debuggerUploadFlushInterval =
        configProvider.getInteger(
            DEBUGGER_UPLOAD_FLUSH_INTERVAL, DEFAULT_DEBUGGER_UPLOAD_FLUSH_INTERVAL);
    debuggerClassFileDumpEnabled =
        configProvider.getBoolean(
            DEBUGGER_CLASSFILE_DUMP_ENABLED, DEFAULT_DEBUGGER_CLASSFILE_DUMP_ENABLED);
    debuggerPollInterval =
        configProvider.getInteger(DEBUGGER_POLL_INTERVAL, DEFAULT_DEBUGGER_POLL_INTERVAL);
    debuggerDiagnosticsInterval =
        configProvider.getInteger(
            DEBUGGER_DIAGNOSTICS_INTERVAL, DEFAULT_DEBUGGER_DIAGNOSTICS_INTERVAL);
    debuggerMetricEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(
                DEBUGGER_METRICS_ENABLED, DEFAULT_DEBUGGER_METRICS_ENABLED);
    debuggerProbeFileLocation = configProvider.getString(DEBUGGER_PROBE_FILE_LOCATION);
    debuggerUploadBatchSize =
        configProvider.getInteger(DEBUGGER_UPLOAD_BATCH_SIZE, DEFAULT_DEBUGGER_UPLOAD_BATCH_SIZE);
    debuggerMaxPayloadSize =
        configProvider.getInteger(DEBUGGER_MAX_PAYLOAD_SIZE, DEFAULT_DEBUGGER_MAX_PAYLOAD_SIZE)
            * 1024;
    debuggerVerifyByteCode =
        configProvider.getBoolean(DEBUGGER_VERIFY_BYTECODE, DEFAULT_DEBUGGER_VERIFY_BYTECODE);
    debuggerInstrumentTheWorld =
        configProvider.getBoolean(
            DEBUGGER_INSTRUMENT_THE_WORLD, DEFAULT_DEBUGGER_INSTRUMENT_THE_WORLD);
    debuggerExcludeFile = configProvider.getString(DEBUGGER_EXCLUDE_FILE);

    awsPropagationEnabled = isPropagationEnabled(true, "aws");
    sqsPropagationEnabled = awsPropagationEnabled && isPropagationEnabled(true, "sqs");

    kafkaClientPropagationEnabled = isPropagationEnabled(true, "kafka", "kafka.client");
    kafkaClientPropagationDisabledTopics =
        tryMakeImmutableSet(configProvider.getList(KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS));
    kafkaClientBase64DecodingEnabled =
        configProvider.getBoolean(KAFKA_CLIENT_BASE64_DECODING_ENABLED, false);

    jmsPropagationEnabled = isPropagationEnabled(true, "jms");
    jmsPropagationDisabledTopics =
        tryMakeImmutableSet(configProvider.getList(JMS_PROPAGATION_DISABLED_TOPICS));
    jmsPropagationDisabledQueues =
        tryMakeImmutableSet(configProvider.getList(JMS_PROPAGATION_DISABLED_QUEUES));

    rabbitPropagationEnabled = isPropagationEnabled(true, "rabbit", "rabbitmq");
    rabbitPropagationDisabledQueues =
        tryMakeImmutableSet(configProvider.getList(RABBIT_PROPAGATION_DISABLED_QUEUES));
    rabbitPropagationDisabledExchanges =
        tryMakeImmutableSet(configProvider.getList(RABBIT_PROPAGATION_DISABLED_EXCHANGES));
    rabbitIncludeRoutingKeyInResource =
        configProvider.getBoolean(RABBIT_INCLUDE_ROUTINGKEY_IN_RESOURCE, true);

    messageBrokerSplitByDestination =
        configProvider.getBoolean(MESSAGE_BROKER_SPLIT_BY_DESTINATION, false);

    grpcIgnoredInboundMethods =
        tryMakeImmutableSet(configProvider.getList(GRPC_IGNORED_INBOUND_METHODS));
    grpcIgnoredOutboundMethods =
        tryMakeImmutableSet(configProvider.getList(GRPC_IGNORED_OUTBOUND_METHODS));
    grpcServerTrimPackageResource =
        configProvider.getBoolean(GRPC_SERVER_TRIM_PACKAGE_RESOURCE, false);
    grpcServerErrorStatuses =
        configProvider.getIntegerRange(
            GRPC_SERVER_ERROR_STATUSES, DEFAULT_GRPC_SERVER_ERROR_STATUSES);
    grpcClientErrorStatuses =
        configProvider.getIntegerRange(
            GRPC_CLIENT_ERROR_STATUSES, DEFAULT_GRPC_CLIENT_ERROR_STATUSES);

    hystrixTagsEnabled = configProvider.getBoolean(HYSTRIX_TAGS_ENABLED, false);
    hystrixMeasuredEnabled = configProvider.getBoolean(HYSTRIX_MEASURED_ENABLED, false);

    igniteCacheIncludeKeys = configProvider.getBoolean(IGNITE_CACHE_INCLUDE_KEYS, false);

    obfuscationQueryRegexp = configProvider.getString(OBFUSCATION_QUERY_STRING_REGEXP);

    playReportHttpStatus = configProvider.getBoolean(PLAY_REPORT_HTTP_STATUS, false);

    servletPrincipalEnabled = configProvider.getBoolean(SERVLET_PRINCIPAL_ENABLED, false);

    xDatadogTagsMaxLength =
        configProvider.getInteger(
            TRACE_X_DATADOG_TAGS_MAX_LENGTH, DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH);

    servletAsyncTimeoutError = configProvider.getBoolean(SERVLET_ASYNC_TIMEOUT_ERROR, true);

    debugEnabled = isDebugMode();

    cwsEnabled = configProvider.getBoolean(CWS_ENABLED, DEFAULT_CWS_ENABLED);
    cwsTlsRefresh = configProvider.getInteger(CWS_TLS_REFRESH, DEFAULT_CWS_TLS_REFRESH);

    dataStreamsEnabled =
        configProvider.getBoolean(DATA_STREAMS_ENABLED, DEFAULT_DATA_STREAMS_ENABLED);

    azureAppServices = configProvider.getBoolean(AZURE_APP_SERVICES, false);
    traceAgentPath = configProvider.getString(TRACE_AGENT_PATH);
    String traceAgentArgsString = configProvider.getString(TRACE_AGENT_ARGS);
    if (traceAgentArgsString == null) {
      traceAgentArgs = Collections.emptyList();
    } else {
      traceAgentArgs =
          Collections.unmodifiableList(
              new ArrayList<>(parseStringIntoSetOfNonEmptyStrings(traceAgentArgsString)));
    }

    dogStatsDPath = configProvider.getString(DOGSTATSD_PATH);
    String dogStatsDArgsString = configProvider.getString(DOGSTATSD_ARGS);
    if (dogStatsDArgsString == null) {
      dogStatsDArgs = Collections.emptyList();
    } else {
      dogStatsDArgs =
          Collections.unmodifiableList(
              new ArrayList<>(parseStringIntoSetOfNonEmptyStrings(dogStatsDArgsString)));
    }

    // Setting this last because we have a few places where this can come from
    apiKey = tmpApiKey;

    if (profilingAgentless && apiKey == null) {
      log.warn(
          "Agentless profiling activated but no api key provided. Profile uploading will likely fail");
    }

    if (isCiVisibilityEnabled()
        && ciVisibilityAgentlessEnabled
        && (apiKey == null || apiKey.isEmpty())) {
      throw new FatalAgentMisconfigurationError(
          "Attempt to start in Agentless mode without API key. "
              + "Please ensure that either an API key is configured, or the tracer is set up to work with the Agent");
    }

    log.debug("New instance: {}", this);
  }

  public ConfigProvider configProvider() {
    return configProvider;
  }

  public long getStartTimeMillis() {
    return startTimeMillis;
  }

  public String getRuntimeId() {
    return runtimeIdEnabled ? RuntimeIdHolder.runtimeId : "";
  }

  public Long getProcessId() {
    return PidHelper.getPidAsLong();
  }

  public String getRuntimeVersion() {
    return runtimeVersion;
  }

  public String getApiKey() {
    return apiKey;
  }

  public String getSite() {
    return site;
  }

  public String getHostName() {
    return HostNameHolder.hostName;
  }

  public String getServiceName() {
    return serviceName;
  }

  public boolean isServiceNameSetByUser() {
    return serviceNameSetByUser;
  }

  public String getRootContextServiceName() {
    return rootContextServiceName;
  }

  public boolean isTraceEnabled() {
    return instrumenterConfig.isTraceEnabled();
  }

  public boolean isIntegrationSynapseLegacyOperationName() {
    return integrationSynapseLegacyOperationName;
  }

  public String getWriterType() {
    return writerType;
  }

  public boolean isAgentConfiguredUsingDefault() {
    return agentConfiguredUsingDefault;
  }

  public String getAgentUrl() {
    return agentUrl;
  }

  public String getAgentHost() {
    return agentHost;
  }

  public int getAgentPort() {
    return agentPort;
  }

  public String getAgentUnixDomainSocket() {
    return agentUnixDomainSocket;
  }

  public String getAgentNamedPipe() {
    return agentNamedPipe;
  }

  public int getAgentTimeout() {
    return agentTimeout;
  }

  public Set<String> getNoProxyHosts() {
    return noProxyHosts;
  }

  public boolean isPrioritySamplingEnabled() {
    return prioritySamplingEnabled;
  }

  public String getPrioritySamplingForce() {
    return prioritySamplingForce;
  }

  public boolean isTraceResolverEnabled() {
    return traceResolverEnabled;
  }

  public Set<String> getIastWeakHashAlgorithms() {
    return iastWeakHashAlgorithms;
  }

  public Pattern getIastWeakCipherAlgorithms() {
    return iastWeakCipherAlgorithms;
  }

  public boolean isIastDeduplicationEnabled() {
    return iastDeduplicationEnabled;
  }

  public Map<String, String> getServiceMapping() {
    return serviceMapping;
  }

  public Map<String, String> getRequestHeaderTags() {
    return requestHeaderTags;
  }

  public Map<String, String> getResponseHeaderTags() {
    return responseHeaderTags;
  }

  public Map<String, String> getBaggageMapping() {
    return baggageMapping;
  }

  public Map<String, String> getHttpServerPathResourceNameMapping() {
    return httpServerPathResourceNameMapping;
  }

  public BitSet getHttpServerErrorStatuses() {
    return httpServerErrorStatuses;
  }

  public BitSet getHttpClientErrorStatuses() {
    return httpClientErrorStatuses;
  }

  public boolean isHttpServerTagQueryString() {
    return httpServerTagQueryString;
  }

  public boolean isHttpServerRawQueryString() {
    return httpServerRawQueryString;
  }

  public boolean isHttpServerRawResource() {
    return httpServerRawResource;
  }

  public boolean isHttpServerRouteBasedNaming() {
    return httpServerRouteBasedNaming;
  }

  public boolean isHttpClientTagQueryString() {
    return httpClientTagQueryString;
  }

  public boolean isHttpClientSplitByDomain() {
    return httpClientSplitByDomain;
  }

  public boolean isDbClientSplitByInstance() {
    return dbClientSplitByInstance;
  }

  public boolean isDbClientSplitByInstanceTypeSuffix() {
    return dbClientSplitByInstanceTypeSuffix;
  }

  public Set<String> getSplitByTags() {
    return splitByTags;
  }

  public int getScopeDepthLimit() {
    return scopeDepthLimit;
  }

  public boolean isScopeStrictMode() {
    return scopeStrictMode;
  }

  public boolean isScopeInheritAsyncPropagation() {
    return scopeInheritAsyncPropagation;
  }

  public int getScopeIterationKeepAlive() {
    return scopeIterationKeepAlive;
  }

  public int getPartialFlushMinSpans() {
    return partialFlushMinSpans;
  }

  public boolean isTraceStrictWritesEnabled() {
    return traceStrictWritesEnabled;
  }

  public boolean isLogExtractHeaderNames() {
    return logExtractHeaderNames;
  }

  @Deprecated
  public Set<PropagationStyle> getPropagationStylesToExtract() {
    return propagationStylesToExtract;
  }

  @Deprecated
  public Set<PropagationStyle> getPropagationStylesToInject() {
    return propagationStylesToInject;
  }

  public Set<TracePropagationStyle> getTracePropagationStylesToExtract() {
    return tracePropagationStylesToExtract;
  }

  public Set<TracePropagationStyle> getTracePropagationStylesToInject() {
    return tracePropagationStylesToInject;
  }

  public int getClockSyncPeriod() {
    return clockSyncPeriod;
  }

  public String getDogStatsDNamedPipe() {
    return dogStatsDNamedPipe;
  }

  public int getDogStatsDStartDelay() {
    return dogStatsDStartDelay;
  }

  public boolean isJmxFetchEnabled() {
    return jmxFetchEnabled;
  }

  public String getJmxFetchConfigDir() {
    return jmxFetchConfigDir;
  }

  public List<String> getJmxFetchConfigs() {
    return jmxFetchConfigs;
  }

  public List<String> getJmxFetchMetricsConfigs() {
    return jmxFetchMetricsConfigs;
  }

  public Integer getJmxFetchCheckPeriod() {
    return jmxFetchCheckPeriod;
  }

  public Integer getJmxFetchRefreshBeansPeriod() {
    return jmxFetchRefreshBeansPeriod;
  }

  public Integer getJmxFetchInitialRefreshBeansPeriod() {
    return jmxFetchInitialRefreshBeansPeriod;
  }

  public String getJmxFetchStatsdHost() {
    return jmxFetchStatsdHost;
  }

  public Integer getJmxFetchStatsdPort() {
    return jmxFetchStatsdPort;
  }

  public boolean isJmxFetchMultipleRuntimeServicesEnabled() {
    return jmxFetchMultipleRuntimeServicesEnabled;
  }

  public int getJmxFetchMultipleRuntimeServicesLimit() {
    return jmxFetchMultipleRuntimeServicesLimit;
  }

  public boolean isHealthMetricsEnabled() {
    return healthMetricsEnabled;
  }

  public String getHealthMetricsStatsdHost() {
    return healthMetricsStatsdHost;
  }

  public Integer getHealthMetricsStatsdPort() {
    return healthMetricsStatsdPort;
  }

  public boolean isPerfMetricsEnabled() {
    return perfMetricsEnabled;
  }

  public boolean isTracerMetricsEnabled() {
    return tracerMetricsEnabled;
  }

  public boolean isTracerMetricsBufferingEnabled() {
    return tracerMetricsBufferingEnabled;
  }

  public int getTracerMetricsMaxAggregates() {
    return tracerMetricsMaxAggregates;
  }

  public int getTracerMetricsMaxPending() {
    return tracerMetricsMaxPending;
  }

  public boolean isLogsInjectionEnabled() {
    return instrumenterConfig.isLogsInjectionEnabled();
  }

  public boolean isReportHostName() {
    return reportHostName;
  }

  public boolean isTraceAnalyticsEnabled() {
    return traceAnalyticsEnabled;
  }

  public String getTraceClientIpHeader() {
    return traceClientIpHeader;
  }

  // whether to collect headers and run the client ip resolution (also requires appsec to be enabled
  // or clientIpEnabled)
  public boolean isTraceClientIpResolverEnabled() {
    return traceClientIpResolverEnabled;
  }

  public Map<String, String> getTraceSamplingServiceRules() {
    return traceSamplingServiceRules;
  }

  public Map<String, String> getTraceSamplingOperationRules() {
    return traceSamplingOperationRules;
  }

  public String getTraceSamplingRules() {
    return traceSamplingRules;
  }

  public Double getTraceSampleRate() {
    return traceSampleRate;
  }

  public int getTraceRateLimit() {
    return traceRateLimit;
  }

  public String getSpanSamplingRules() {
    return spanSamplingRules;
  }

  public String getSpanSamplingRulesFile() {
    return spanSamplingRulesFile;
  }

  public boolean isProfilingEnabled() {
    return instrumenterConfig.isProfilingEnabled();
  }

  public boolean isProfilingAgentless() {
    return profilingAgentless;
  }

  public int getProfilingStartDelay() {
    return profilingStartDelay;
  }

  public boolean isProfilingStartForceFirst() {
    return profilingStartForceFirst;
  }

  public int getProfilingUploadPeriod() {
    return profilingUploadPeriod;
  }

  public String getProfilingTemplateOverrideFile() {
    return profilingTemplateOverrideFile;
  }

  public int getProfilingUploadTimeout() {
    return profilingUploadTimeout;
  }

  public String getProfilingUploadCompression() {
    return profilingUploadCompression;
  }

  public String getProfilingProxyHost() {
    return profilingProxyHost;
  }

  public int getProfilingProxyPort() {
    return profilingProxyPort;
  }

  public String getProfilingProxyUsername() {
    return profilingProxyUsername;
  }

  public String getProfilingProxyPassword() {
    return profilingProxyPassword;
  }

  public int getProfilingExceptionSampleLimit() {
    return profilingExceptionSampleLimit;
  }

  public int getProfilingDirectAllocationSampleLimit() {
    return profilingDirectAllocationSampleLimit;
  }

  public int getProfilingExceptionHistogramTopItems() {
    return profilingExceptionHistogramTopItems;
  }

  public int getProfilingExceptionHistogramMaxCollectionSize() {
    return profilingExceptionHistogramMaxCollectionSize;
  }

  public boolean isProfilingExcludeAgentThreads() {
    return profilingExcludeAgentThreads;
  }

  public boolean isProfilingUploadSummaryOn413Enabled() {
    return profilingUploadSummaryOn413Enabled;
  }

  public boolean isDatadogProfilerEnabled() {
    return isDatadogProfilerEnabled;
  }

  public static boolean isDatadogProfilerSafeInCurrentEnvironment() {
    // don't want to put this logic (which will evolve) in the public ProfilingConfig, and can't
    // access Platform there
    return Platform.isJ9()
        || Platform.isJavaVersionAtLeast(17, 0, 5)
        || (Platform.isJavaVersion(11) && Platform.isJavaVersionAtLeast(11, 0, 17))
        || (Platform.isJavaVersion(8) && Platform.isJavaVersionAtLeast(8, 0, 352));
  }

  public boolean isCrashTrackingAgentless() {
    return crashTrackingAgentless;
  }

  public boolean isTelemetryEnabled() {
    return instrumenterConfig.isTelemetryEnabled();
  }

  public int getTelemetryHeartbeatInterval() {
    return telemetryHeartbeatInterval;
  }

  public boolean isTelemetryDependencyServiceEnabled() {
    return isTelemetryDependencyServiceEnabled;
  }

  public boolean isClientIpEnabled() {
    return clientIpEnabled;
  }

  public ProductActivation getAppSecActivation() {
    return instrumenterConfig.getAppSecActivation();
  }

  public boolean isAppSecReportingInband() {
    return appSecReportingInband;
  }

  public int getAppSecReportMinTimeout() {
    return appSecReportMinTimeout;
  }

  public int getAppSecReportMaxTimeout() {
    return appSecReportMaxTimeout;
  }

  public int getAppSecTraceRateLimit() {
    return appSecTraceRateLimit;
  }

  public boolean isAppSecWafMetrics() {
    return appSecWafMetrics;
  }

  // in microseconds
  public int getAppSecWafTimeout() {
    return appSecWafTimeout;
  }

  public String getAppSecObfuscationParameterKeyRegexp() {
    return appSecObfuscationParameterKeyRegexp;
  }

  public String getAppSecObfuscationParameterValueRegexp() {
    return appSecObfuscationParameterValueRegexp;
  }

  public String getAppSecHttpBlockedTemplateHtml() {
    return appSecHttpBlockedTemplateHtml;
  }

  public String getAppSecHttpBlockedTemplateJson() {
    return appSecHttpBlockedTemplateJson;
  }

  public boolean isIastEnabled() {
    return instrumenterConfig.isIastEnabled();
  }

  public boolean isIastDebugEnabled() {
    return iastDebugEnabled;
  }

  public int getIastMaxConcurrentRequests() {
    return iastMaxConcurrentRequests;
  }

  public int getIastVulnerabilitiesPerRequest() {
    return iastVulnerabilitiesPerRequest;
  }

  public float getIastRequestSampling() {
    return iastRequestSampling;
  }

  public boolean isCiVisibilityEnabled() {
    return instrumenterConfig.isCiVisibilityEnabled();
  }

  public boolean isCiVisibilityAgentlessEnabled() {
    return ciVisibilityAgentlessEnabled;
  }

  public String getCiVisibilityAgentlessUrl() {
    return ciVisibilityAgentlessUrl;
  }

  public String getAppSecRulesFile() {
    return appSecRulesFile;
  }

  public long getRemoteConfigMaxPayloadSizeBytes() {
    return remoteConfigMaxPayloadSize;
  }

  public boolean isRemoteConfigEnabled() {
    return remoteConfigEnabled;
  }

  public boolean isRemoteConfigIntegrityCheckEnabled() {
    return remoteConfigIntegrityCheckEnabled;
  }

  public String getFinalRemoteConfigUrl() {
    return remoteConfigUrl;
  }

  public int getRemoteConfigInitialPollInterval() {
    return remoteConfigInitialPollInterval;
  }

  public String getRemoteConfigTargetsKeyId() {
    return remoteConfigTargetsKeyId;
  }

  public String getRemoteConfigTargetsKey() {
    return remoteConfigTargetsKey;
  }

  public boolean isDebuggerEnabled() {
    return debuggerEnabled;
  }

  public int getDebuggerUploadTimeout() {
    return debuggerUploadTimeout;
  }

  public int getDebuggerUploadFlushInterval() {
    return debuggerUploadFlushInterval;
  }

  public boolean isDebuggerClassFileDumpEnabled() {
    return debuggerClassFileDumpEnabled;
  }

  public int getDebuggerPollInterval() {
    return debuggerPollInterval;
  }

  public int getDebuggerDiagnosticsInterval() {
    return debuggerDiagnosticsInterval;
  }

  public boolean isDebuggerMetricsEnabled() {
    return debuggerMetricEnabled;
  }

  public int getDebuggerUploadBatchSize() {
    return debuggerUploadBatchSize;
  }

  public long getDebuggerMaxPayloadSize() {
    return debuggerMaxPayloadSize;
  }

  public boolean isDebuggerVerifyByteCode() {
    return debuggerVerifyByteCode;
  }

  public boolean isDebuggerInstrumentTheWorld() {
    return debuggerInstrumentTheWorld;
  }

  public String getDebuggerExcludeFile() {
    return debuggerExcludeFile;
  }

  public String getFinalDebuggerProbeUrl() {
    // by default poll from datadog agent
    return "http://" + agentHost + ":" + agentPort;
  }

  public String getFinalDebuggerSnapshotUrl() {
    // by default send to datadog agent
    return agentUrl + "/debugger/v1/input";
  }

  public String getDebuggerProbeFileLocation() {
    return debuggerProbeFileLocation;
  }

  public boolean isAwsPropagationEnabled() {
    return awsPropagationEnabled;
  }

  public boolean isSqsPropagationEnabled() {
    return sqsPropagationEnabled;
  }

  public boolean isKafkaClientPropagationEnabled() {
    return kafkaClientPropagationEnabled;
  }

  public boolean isKafkaClientPropagationDisabledForTopic(String topic) {
    return null != topic && kafkaClientPropagationDisabledTopics.contains(topic);
  }

  public boolean isJmsPropagationEnabled() {
    return jmsPropagationEnabled;
  }

  public boolean isJmsPropagationDisabledForDestination(final String queueOrTopic) {
    return null != queueOrTopic
        && (jmsPropagationDisabledQueues.contains(queueOrTopic)
            || jmsPropagationDisabledTopics.contains(queueOrTopic));
  }

  public boolean isKafkaClientBase64DecodingEnabled() {
    return kafkaClientBase64DecodingEnabled;
  }

  public boolean isRabbitPropagationEnabled() {
    return rabbitPropagationEnabled;
  }

  public boolean isRabbitPropagationDisabledForDestination(final String queueOrExchange) {
    return null != queueOrExchange
        && (rabbitPropagationDisabledQueues.contains(queueOrExchange)
            || rabbitPropagationDisabledExchanges.contains(queueOrExchange));
  }

  public boolean isRabbitIncludeRoutingKeyInResource() {
    return rabbitIncludeRoutingKeyInResource;
  }

  public boolean isMessageBrokerSplitByDestination() {
    return messageBrokerSplitByDestination;
  }

  public boolean isHystrixTagsEnabled() {
    return hystrixTagsEnabled;
  }

  public boolean isHystrixMeasuredEnabled() {
    return hystrixMeasuredEnabled;
  }

  public boolean isIgniteCacheIncludeKeys() {
    return igniteCacheIncludeKeys;
  }

  public String getObfuscationQueryRegexp() {
    return obfuscationQueryRegexp;
  }

  public boolean getPlayReportHttpStatus() {
    return playReportHttpStatus;
  }

  public boolean isServletPrincipalEnabled() {
    return servletPrincipalEnabled;
  }

  public boolean isSpringDataRepositoryInterfaceResourceName() {
    return springDataRepositoryInterfaceResourceName;
  }

  public int getxDatadogTagsMaxLength() {
    return xDatadogTagsMaxLength;
  }

  public boolean isServletAsyncTimeoutError() {
    return servletAsyncTimeoutError;
  }

  public boolean isTraceAgentV05Enabled() {
    return traceAgentV05Enabled;
  }

  public boolean isDebugEnabled() {
    return debugEnabled;
  }

  public boolean isCwsEnabled() {
    return cwsEnabled;
  }

  public int getCwsTlsRefresh() {
    return cwsTlsRefresh;
  }

  public boolean isAzureAppServices() {
    return azureAppServices;
  }

  public boolean isDataStreamsEnabled() {
    return dataStreamsEnabled;
  }

  public String getTraceAgentPath() {
    return traceAgentPath;
  }

  public List<String> getTraceAgentArgs() {
    return traceAgentArgs;
  }

  public String getDogStatsDPath() {
    return dogStatsDPath;
  }

  public List<String> getDogStatsDArgs() {
    return dogStatsDArgs;
  }

  public String getConfigFileStatus() {
    return configFileStatus;
  }

  public IdGenerationStrategy getIdGenerationStrategy() {
    return idGenerationStrategy;
  }

  public Set<String> getGrpcIgnoredInboundMethods() {
    return grpcIgnoredInboundMethods;
  }

  public Set<String> getGrpcIgnoredOutboundMethods() {
    return grpcIgnoredOutboundMethods;
  }

  public boolean isGrpcServerTrimPackageResource() {
    return grpcServerTrimPackageResource;
  }

  public BitSet getGrpcServerErrorStatuses() {
    return grpcServerErrorStatuses;
  }

  public BitSet getGrpcClientErrorStatuses() {
    return grpcClientErrorStatuses;
  }

  /** @return A map of tags to be applied only to the local application root span. */
  public Map<String, Object> getLocalRootSpanTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final Map<String, Object> result = new HashMap<>(runtimeTags.size() + 1);
    result.putAll(runtimeTags);
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);

    if (reportHostName) {
      final String hostName = getHostName();
      if (null != hostName && !hostName.isEmpty()) {
        result.put(INTERNAL_HOST_NAME, hostName);
      }
    }

    if (azureAppServices) {
      result.putAll(getAzureAppServicesTags());
    }

    result.putAll(getProcessIdTag());

    return Collections.unmodifiableMap(result);
  }

  public WellKnownTags getWellKnownTags() {
    return new WellKnownTags(
        getRuntimeId(),
        reportHostName ? getHostName() : "",
        getEnv(),
        serviceName,
        getVersion(),
        LANGUAGE_TAG_VALUE);
  }

  public String getPrimaryTag() {
    return primaryTag;
  }

  public Set<String> getMetricsIgnoredResources() {
    return tryMakeImmutableSet(configProvider.getList(TRACER_METRICS_IGNORED_RESOURCES));
  }

  public String getEnv() {
    // intentionally not thread safe
    if (env == null) {
      env = getMergedSpanTags().get("env");
      if (env == null) {
        env = "";
      }
    }

    return env;
  }

  public String getVersion() {
    // intentionally not thread safe
    if (version == null) {
      version = getMergedSpanTags().get("version");
      if (version == null) {
        version = "";
      }
    }

    return version;
  }

  public Map<String, String> getMergedSpanTags() {
    // Do not include runtimeId into span tags: we only want that added to the root span
    final Map<String, String> result = newHashMap(getGlobalTags().size() + spanTags.size());
    result.putAll(getGlobalTags());
    result.putAll(spanTags);
    return Collections.unmodifiableMap(result);
  }

  public Map<String, String> getMergedJmxTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final Map<String, String> result =
        newHashMap(
            getGlobalTags().size() + jmxTags.size() + runtimeTags.size() + 1 /* for serviceName */);
    result.putAll(getGlobalTags());
    result.putAll(jmxTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may chose to override it.
    // Additionally, infra/JMX metrics require `service` rather than APM's `service.name` tag
    result.put(SERVICE_TAG, serviceName);
    return Collections.unmodifiableMap(result);
  }

  public Map<String, String> getMergedProfilingTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final String host = getHostName();
    final Map<String, String> result =
        newHashMap(
            getGlobalTags().size()
                + profilingTags.size()
                + runtimeTags.size()
                + 4 /* for serviceName and host and language and runtime_version */);
    result.put(HOST_TAG, host); // Host goes first to allow to override it
    result.putAll(getGlobalTags());
    result.putAll(profilingTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may chose to override it.
    result.put(SERVICE_TAG, serviceName);
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    result.put(RUNTIME_VERSION_TAG, runtimeVersion);
    return Collections.unmodifiableMap(result);
  }

  public Map<String, String> getMergedCrashTrackingTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final String host = getHostName();
    final Map<String, String> result =
        newHashMap(
            getGlobalTags().size()
                + crashTrackingTags.size()
                + runtimeTags.size()
                + 3 /* for serviceName and host and language */);
    result.put(HOST_TAG, host); // Host goes first to allow to override it
    result.putAll(getGlobalTags());
    result.putAll(crashTrackingTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may chose to override it.
    result.put(SERVICE_TAG, serviceName);
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    return Collections.unmodifiableMap(result);
  }

  /**
   * Returns the sample rate for the specified instrumentation or {@link
   * ConfigDefaults#DEFAULT_ANALYTICS_SAMPLE_RATE} if none specified.
   */
  public float getInstrumentationAnalyticsSampleRate(final String... aliases) {
    for (final String alias : aliases) {
      final String configKey = alias + ".analytics.sample-rate";
      final Float rate = configProvider.getFloat("trace." + configKey, configKey);
      if (null != rate) {
        return rate;
      }
    }
    return DEFAULT_ANALYTICS_SAMPLE_RATE;
  }

  /**
   * Provide 'global' tags, i.e. tags set everywhere. We have to support old (dd.trace.global.tags)
   * version of this setting if new (dd.tags) version has not been specified.
   */
  public Map<String, String> getGlobalTags() {
    return tags;
  }

  /**
   * Return a map of tags required by the datadog backend to link runtime metrics (i.e. jmx) and
   * traces.
   *
   * <p>These tags must be applied to every runtime metrics and placed on the root span of every
   * trace.
   *
   * @return A map of tag-name -> tag-value
   */
  private Map<String, String> getRuntimeTags() {
    return Collections.singletonMap(RUNTIME_ID_TAG, getRuntimeId());
  }

  private Map<String, Long> getProcessIdTag() {
    return Collections.singletonMap(PID_TAG, getProcessId());
  }

  private Map<String, String> getAzureAppServicesTags() {
    // These variable names and derivations are copied from the dotnet tracer
    // See
    // https://github.com/DataDog/dd-trace-dotnet/blob/master/tracer/src/Datadog.Trace/PlatformHelpers/AzureAppServices.cs
    // and
    // https://github.com/DataDog/dd-trace-dotnet/blob/master/tracer/src/Datadog.Trace/TraceContext.cs#L207
    Map<String, String> aasTags = new HashMap<>();

    /// The site name of the site instance in Azure where the traced application is running.
    String siteName = getEnv("WEBSITE_SITE_NAME");
    if (siteName != null) {
      aasTags.put("aas.site.name", siteName);
    }

    // The kind of application instance running in Azure.
    // Possible values: app, api, mobileapp, app_linux, app_linux_container, functionapp,
    // functionapp_linux, functionapp_linux_container

    // The type of application instance running in Azure.
    // Possible values: app, function
    if (getEnv("FUNCTIONS_WORKER_RUNTIME") != null
        || getEnv("FUNCTIONS_EXTENSIONS_VERSION") != null) {
      aasTags.put("aas.site.kind", "functionapp");
      aasTags.put("aas.site.type", "function");
    } else {
      aasTags.put("aas.site.kind", "app");
      aasTags.put("aas.site.type", "app");
    }

    //  The resource group of the site instance in Azure App Services
    String resourceGroup = getEnv("WEBSITE_RESOURCE_GROUP");
    if (resourceGroup != null) {
      aasTags.put("aas.resource.group", resourceGroup);
    }

    // Example: 8c500027-5f00-400e-8f00-60000000000f+apm-dotnet-EastUSwebspace
    // Format: {subscriptionId}+{planResourceGroup}-{hostedInRegion}
    String websiteOwner = getEnv("WEBSITE_OWNER_NAME");
    int plusIndex = websiteOwner == null ? -1 : websiteOwner.indexOf("+");

    // The subscription ID of the site instance in Azure App Services
    String subscriptionId = null;
    if (plusIndex > 0) {
      subscriptionId = websiteOwner.substring(0, plusIndex);
      aasTags.put("aas.subscription.id", subscriptionId);
    }

    if (subscriptionId != null && siteName != null && resourceGroup != null) {
      // The resource ID of the site instance in Azure App Services
      String resourceId =
          "/subscriptions/"
              + subscriptionId
              + "/resourcegroups/"
              + resourceGroup
              + "/providers/microsoft.web/sites/"
              + siteName;
      resourceId = resourceId.toLowerCase();
      aasTags.put("aas.resource.id", resourceId);
    } else {
      log.warn(
          "Unable to generate resource id subscription id: {}, site name: {}, resource group {}",
          subscriptionId,
          siteName,
          resourceGroup);
    }

    // The instance ID in Azure
    String instanceId = getEnv("WEBSITE_INSTANCE_ID");
    instanceId = instanceId == null ? "unknown" : instanceId;
    aasTags.put("aas.environment.instance_id", instanceId);

    // The instance name in Azure
    String instanceName = getEnv("COMPUTERNAME");
    instanceName = instanceName == null ? "unknown" : instanceName;
    aasTags.put("aas.environment.instance_name", instanceName);

    // The operating system in Azure
    String operatingSystem = getEnv("WEBSITE_OS");
    operatingSystem = operatingSystem == null ? "unknown" : operatingSystem;
    aasTags.put("aas.environment.os", operatingSystem);

    // The version of the extension installed
    String siteExtensionVersion = getEnv("DD_AAS_JAVA_EXTENSION_VERSION");
    siteExtensionVersion = siteExtensionVersion == null ? "unknown" : siteExtensionVersion;
    aasTags.put("aas.environment.extension_version", siteExtensionVersion);

    aasTags.put("aas.environment.runtime", getProp("java.vm.name", "unknown"));

    return aasTags;
  }

  public String getFinalProfilingUrl() {
    if (profilingUrl != null) {
      // when profilingUrl is set we use it regardless of apiKey/agentless config
      return profilingUrl;
    } else if (profilingAgentless) {
      // when agentless profiling is turned on we send directly to our intake
      return "https://intake.profile." + site + "/api/v2/profile";
    } else {
      // when profilingUrl and agentless are not set we send to the dd trace agent running locally
      return "http://" + agentHost + ":" + agentPort + "/profiling/v1/input";
    }
  }

  public String getFinalCrashTrackingTelemetryUrl() {
    if (crashTrackingAgentless) {
      // when agentless crashTracking is turned on we send directly to our intake
      return "https://all-http-intake.logs." + site + "/api/v2/apmtelemetry";
    } else {
      // when agentless are not set we send to the dd trace agent running locally
      return "http://" + agentHost + ":" + agentPort + "/telemetry/proxy/api/v2/apmtelemetry";
    }
  }

  public boolean isJmxFetchIntegrationEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return configProvider.isEnabled(integrationNames, "jmxfetch.", ".enabled", defaultEnabled);
  }

  public boolean isRuleEnabled(final String name) {
    return isRuleEnabled(name, true);
  }

  public boolean isRuleEnabled(final String name, boolean defaultEnabled) {
    boolean enabled = configProvider.getBoolean("trace." + name + ".enabled", defaultEnabled);
    boolean lowerEnabled =
        configProvider.getBoolean("trace." + name.toLowerCase() + ".enabled", defaultEnabled);
    return defaultEnabled ? enabled && lowerEnabled : enabled || lowerEnabled;
  }

  /**
   * @param integrationNames
   * @param defaultEnabled
   * @return
   * @deprecated This method should only be used internally. Use the instance getter instead {@link
   *     #isJmxFetchIntegrationEnabled(Iterable, boolean)}.
   */
  public static boolean jmxFetchIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return Config.get().isJmxFetchIntegrationEnabled(integrationNames, defaultEnabled);
  }

  public boolean isEndToEndDurationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return configProvider.isEnabled(
        Arrays.asList(integrationNames), "", ".e2e.duration.enabled", defaultEnabled);
  }

  public boolean isPropagationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return configProvider.isEnabled(
        Arrays.asList(integrationNames), "", ".propagation.enabled", defaultEnabled);
  }

  public boolean isLegacyTracingEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return configProvider.isEnabled(
        Arrays.asList(integrationNames), "", ".legacy.tracing.enabled", defaultEnabled);
  }

  public boolean isEnabled(
      final boolean defaultEnabled, final String settingName, String settingSuffix) {
    return configProvider.isEnabled(
        Collections.singletonList(settingName), "", settingSuffix, defaultEnabled);
  }

  private void logIgnoredSettingWarning(
      String setting, String overridingSetting, String overridingSuffix) {
    log.warn(
        "Setting {} ignored since {}{} is enabled.",
        propertyNameToSystemPropertyName(setting),
        propertyNameToSystemPropertyName(overridingSetting),
        overridingSuffix);
  }

  private void logOverriddenSettingWarning(String setting, String overridingSetting, Object value) {
    log.warn(
        "Setting {} is overridden by setting {} with value {}.",
        propertyNameToSystemPropertyName(setting),
        propertyNameToSystemPropertyName(overridingSetting),
        value);
  }

  private void logOverriddenDeprecatedSettingWarning(
      String setting, String overridingSetting, Object value) {
    log.warn(
        "Setting {} is deprecated and overridden by setting {} with value {}.",
        propertyNameToSystemPropertyName(setting),
        propertyNameToSystemPropertyName(overridingSetting),
        value);
  }

  private void logDeprecatedConvertedSetting(
      String deprecatedSetting, Object oldValue, String newSetting, Object newValue) {
    log.warn(
        "Setting {} is deprecated and the value {} has been converted to {} for setting {}.",
        propertyNameToSystemPropertyName(deprecatedSetting),
        oldValue,
        newValue,
        propertyNameToSystemPropertyName(newSetting));
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return configProvider.isEnabled(integrationNames, "", ".analytics.enabled", defaultEnabled);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return configProvider.isEnabled(
        Arrays.asList(integrationNames), "", ".analytics.enabled", defaultEnabled);
  }

  public boolean isSamplingMechanismValidationDisabled() {
    return configProvider.getBoolean(TracerConfig.SAMPLING_MECHANISM_VALIDATION_DISABLED, false);
  }

  public <T extends Enum<T>> T getEnumValue(
      final String name, final Class<T> type, final T defaultValue) {
    return configProvider.getEnum(name, type, defaultValue);
  }

  private static boolean isDebugMode() {
    final String tracerDebugLevelSysprop = "dd.trace.debug";
    final String tracerDebugLevelProp = getProp(tracerDebugLevelSysprop);

    if (tracerDebugLevelProp != null) {
      return Boolean.parseBoolean(tracerDebugLevelProp);
    }

    final String tracerDebugLevelEnv = getEnv(toEnvVar(tracerDebugLevelSysprop));

    if (tracerDebugLevelEnv != null) {
      return Boolean.parseBoolean(tracerDebugLevelEnv);
    }
    return false;
  }

  /**
   * @param integrationNames
   * @param defaultEnabled
   * @return
   * @deprecated This method should only be used internally. Use the instance getter instead {@link
   *     #isTraceAnalyticsIntegrationEnabled(SortedSet, boolean)}.
   */
  public static boolean traceAnalyticsIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return Config.get().isTraceAnalyticsIntegrationEnabled(integrationNames, defaultEnabled);
  }

  private <T> Set<T> getSettingsSetFromEnvironment(String name, Function<String, T> mapper) {
    final String value = configProvider.getString(name, "");
    return convertStringSetToSet(name, parseStringIntoSetOfNonEmptyStrings(value), mapper);
  }

  private <F, T> Set<T> convertSettingsSet(Set<F> fromSet, Function<F, Iterable<T>> mapper) {
    if (fromSet.isEmpty()) {
      return Collections.emptySet();
    }
    Set<T> result = new LinkedHashSet<>(fromSet.size());
    for (F from : fromSet) {
      for (T to : mapper.apply(from)) {
        result.add(to);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  private static final String PREFIX = "dd.";

  /**
   * Converts the property name, e.g. 'service.name' into a public system property name, e.g.
   * `dd.service.name`.
   *
   * @param setting The setting name, e.g. `service.name`
   * @return The public facing system property name
   */
  @Nonnull
  private static String propertyNameToSystemPropertyName(final String setting) {
    return PREFIX + setting;
  }

  @Nonnull
  private static Map<String, String> newHashMap(final int size) {
    return new HashMap<>(size + 1, 1f);
  }

  /**
   * @param map
   * @param propNames
   * @return new unmodifiable copy of {@param map} where properties are overwritten from environment
   */
  @Nonnull
  private Map<String, String> getMapWithPropertiesDefinedByEnvironment(
      @Nonnull final Map<String, String> map, @Nonnull final String... propNames) {
    final Map<String, String> res = new HashMap<>(map);
    for (final String propName : propNames) {
      final String val = configProvider.getString(propName);
      if (val != null) {
        res.put(propName, val);
      }
    }
    return Collections.unmodifiableMap(res);
  }

  @Nonnull
  private static Set<String> parseStringIntoSetOfNonEmptyStrings(final String str) {
    // Using LinkedHashSet to preserve original string order
    final Set<String> result = new LinkedHashSet<>();
    // Java returns single value when splitting an empty string. We do not need that value, so
    // we need to throw it out.
    int start = 0;
    int i = 0;
    for (; i < str.length(); ++i) {
      char c = str.charAt(i);
      if (Character.isWhitespace(c) || c == ',') {
        if (i - start - 1 > 0) {
          result.add(str.substring(start, i));
        }
        start = i + 1;
      }
    }
    if (i - start - 1 > 0) {
      result.add(str.substring(start));
    }
    return Collections.unmodifiableSet(result);
  }

  private static <T> Set<T> convertStringSetToSet(
      String setting, final Set<String> input, Function<String, T> mapper) {
    if (input.isEmpty()) {
      return Collections.emptySet();
    }
    // Using LinkedHashSet to preserve original string order
    final Set<T> result = new LinkedHashSet<>();
    for (final String value : input) {
      try {
        result.add(mapper.apply(value));
      } catch (final IllegalArgumentException e) {
        log.warn(
            "Cannot recognize config string value {} for setting {}",
            value,
            propertyNameToSystemPropertyName(setting));
      }
    }
    return Collections.unmodifiableSet(result);
  }

  /** Returns the detected hostname. First tries locally, then using DNS */
  static String initHostName() {
    String possibleHostname;

    // Try environment variable.  This works in almost all environments
    if (isWindowsOS()) {
      possibleHostname = getEnv("COMPUTERNAME");
    } else {
      possibleHostname = getEnv("HOSTNAME");
    }

    if (possibleHostname != null && !possibleHostname.isEmpty()) {
      log.debug("Determined hostname from environment variable");
      return possibleHostname.trim();
    }

    // Try hostname files
    final String[] hostNameFiles = new String[] {"/proc/sys/kernel/hostname", "/etc/hostname"};
    for (final String hostNameFile : hostNameFiles) {
      try {
        final Path hostNamePath = FileSystems.getDefault().getPath(hostNameFile);
        if (Files.isRegularFile(hostNamePath)) {
          byte[] bytes = Files.readAllBytes(hostNamePath);
          possibleHostname = new String(bytes, StandardCharsets.ISO_8859_1);
        }
      } catch (Throwable t) {
        // Ignore
      }
      possibleHostname = Strings.trim(possibleHostname);
      if (!possibleHostname.isEmpty()) {
        log.debug("Determined hostname from file {}", hostNameFile);
        return possibleHostname;
      }
    }

    // Try hostname command
    try (final BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(Runtime.getRuntime().exec("hostname").getInputStream()))) {
      possibleHostname = reader.readLine();
    } catch (final Throwable ignore) {
      // Ignore.  Hostname command is not always available
    }

    if (possibleHostname != null && !possibleHostname.isEmpty()) {
      log.debug("Determined hostname from hostname command");
      return possibleHostname.trim();
    }

    // From DNS
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (final UnknownHostException e) {
      // If we are not able to detect the hostname we do not throw an exception.
    }

    return null;
  }

  private static boolean isWindowsOS() {
    return getProp("os.name").startsWith("Windows");
  }

  private static String getEnv(String name) {
    String value = System.getenv(name);
    if (value != null) {
      ConfigCollector.get().put(name, value);
    }
    return value;
  }

  private static Pattern getPattern(String defaultValue, String userValue) {
    try {
      if (userValue != null) {
        return Pattern.compile(userValue);
      }
    } catch (Exception e) {
      log.debug("Cannot create pattern from user value {}", userValue);
    }
    return Pattern.compile(defaultValue);
  }

  private static String getProp(String name) {
    return getProp(name, null);
  }

  private static String getProp(String name, String def) {
    String value = System.getProperty(name, def);
    if (value != null) {
      ConfigCollector.get().put(name, value);
    }
    return value;
  }

  // This has to be placed after all other static fields to give them a chance to initialize
  @SuppressFBWarnings("SI_INSTANCE_BEFORE_FINALS_ASSIGNED")
  private static final Config INSTANCE =
      new Config(ConfigProvider.getInstance(), InstrumenterConfig.get());

  public static Config get() {
    return INSTANCE;
  }

  /**
   * This method is deprecated since the method of configuration will be changed in the future. The
   * properties instance should instead be passed directly into the DDTracer builder:
   *
   * <pre>
   *   DDTracer.builder().withProperties(new Properties()).build()
   * </pre>
   *
   * <p>Config keys for use in Properties instance construction can be found in {@link
   * GeneralConfig} and {@link TracerConfig}.
   *
   * @deprecated
   */
  @Deprecated
  public static Config get(final Properties properties) {
    if (properties == null || properties.isEmpty()) {
      return INSTANCE;
    } else {
      return new Config(ConfigProvider.withPropertiesOverride(properties));
    }
  }

  @Override
  public String toString() {
    return "Config{"
        + "instrumenterConfig="
        + instrumenterConfig
        + ", runtimeId='"
        + getRuntimeId()
        + '\''
        + ", runtimeVersion='"
        + runtimeVersion
        + ", apiKey="
        + (apiKey == null ? "null" : "****")
        + ", site='"
        + site
        + '\''
        + ", hostName='"
        + getHostName()
        + '\''
        + ", serviceName='"
        + serviceName
        + '\''
        + ", serviceNameSetByUser="
        + serviceNameSetByUser
        + ", rootContextServiceName="
        + rootContextServiceName
        + ", integrationSynapseLegacyOperationName="
        + integrationSynapseLegacyOperationName
        + ", writerType='"
        + writerType
        + '\''
        + ", agentConfiguredUsingDefault="
        + agentConfiguredUsingDefault
        + ", agentUrl='"
        + agentUrl
        + '\''
        + ", agentHost='"
        + agentHost
        + '\''
        + ", agentPort="
        + agentPort
        + ", agentUnixDomainSocket='"
        + agentUnixDomainSocket
        + '\''
        + ", agentTimeout="
        + agentTimeout
        + ", noProxyHosts="
        + noProxyHosts
        + ", prioritySamplingEnabled="
        + prioritySamplingEnabled
        + ", prioritySamplingForce='"
        + prioritySamplingForce
        + '\''
        + ", traceResolverEnabled="
        + traceResolverEnabled
        + ", serviceMapping="
        + serviceMapping
        + ", tags="
        + tags
        + ", spanTags="
        + spanTags
        + ", jmxTags="
        + jmxTags
        + ", requestHeaderTags="
        + requestHeaderTags
        + ", responseHeaderTags="
        + responseHeaderTags
        + ", baggageMapping="
        + baggageMapping
        + ", httpServerErrorStatuses="
        + httpServerErrorStatuses
        + ", httpClientErrorStatuses="
        + httpClientErrorStatuses
        + ", httpServerTagQueryString="
        + httpServerTagQueryString
        + ", httpServerRawQueryString="
        + httpServerRawQueryString
        + ", httpServerRawResource="
        + httpServerRawResource
        + ", httpServerRouteBasedNaming="
        + httpServerRouteBasedNaming
        + ", httpServerPathResourceNameMapping="
        + httpServerPathResourceNameMapping
        + ", httpClientTagQueryString="
        + httpClientTagQueryString
        + ", httpClientSplitByDomain="
        + httpClientSplitByDomain
        + ", dbClientSplitByInstance="
        + dbClientSplitByInstance
        + ", dbClientSplitByInstanceTypeSuffix="
        + dbClientSplitByInstanceTypeSuffix
        + ", splitByTags="
        + splitByTags
        + ", scopeDepthLimit="
        + scopeDepthLimit
        + ", scopeStrictMode="
        + scopeStrictMode
        + ", scopeInheritAsyncPropagation="
        + scopeInheritAsyncPropagation
        + ", scopeIterationKeepAlive="
        + scopeIterationKeepAlive
        + ", partialFlushMinSpans="
        + partialFlushMinSpans
        + ", traceStrictWritesEnabled="
        + traceStrictWritesEnabled
        + ", tracePropagationStylesToExtract="
        + tracePropagationStylesToExtract
        + ", tracePropagationStylesToInject="
        + tracePropagationStylesToInject
        + ", clockSyncPeriod="
        + clockSyncPeriod
        + ", jmxFetchEnabled="
        + jmxFetchEnabled
        + ", dogStatsDStartDelay="
        + dogStatsDStartDelay
        + ", jmxFetchConfigDir='"
        + jmxFetchConfigDir
        + '\''
        + ", jmxFetchConfigs="
        + jmxFetchConfigs
        + ", jmxFetchMetricsConfigs="
        + jmxFetchMetricsConfigs
        + ", jmxFetchCheckPeriod="
        + jmxFetchCheckPeriod
        + ", jmxFetchInitialRefreshBeansPeriod="
        + jmxFetchInitialRefreshBeansPeriod
        + ", jmxFetchRefreshBeansPeriod="
        + jmxFetchRefreshBeansPeriod
        + ", jmxFetchStatsdHost='"
        + jmxFetchStatsdHost
        + '\''
        + ", jmxFetchStatsdPort="
        + jmxFetchStatsdPort
        + ", jmxFetchMultipleRuntimeServicesEnabled="
        + jmxFetchMultipleRuntimeServicesEnabled
        + ", jmxFetchMultipleRuntimeServicesLimit="
        + jmxFetchMultipleRuntimeServicesLimit
        + ", healthMetricsEnabled="
        + healthMetricsEnabled
        + ", healthMetricsStatsdHost='"
        + healthMetricsStatsdHost
        + '\''
        + ", healthMetricsStatsdPort="
        + healthMetricsStatsdPort
        + ", perfMetricsEnabled="
        + perfMetricsEnabled
        + ", tracerMetricsEnabled="
        + tracerMetricsEnabled
        + ", tracerMetricsBufferingEnabled="
        + tracerMetricsBufferingEnabled
        + ", tracerMetricsMaxAggregates="
        + tracerMetricsMaxAggregates
        + ", tracerMetricsMaxPending="
        + tracerMetricsMaxPending
        + ", reportHostName="
        + reportHostName
        + ", traceAnalyticsEnabled="
        + traceAnalyticsEnabled
        + ", traceSamplingServiceRules="
        + traceSamplingServiceRules
        + ", traceSamplingOperationRules="
        + traceSamplingOperationRules
        + ", traceSamplingJsonRules="
        + traceSamplingRules
        + ", traceSampleRate="
        + traceSampleRate
        + ", traceRateLimit="
        + traceRateLimit
        + ", spanSamplingRules="
        + spanSamplingRules
        + ", spanSamplingRulesFile="
        + spanSamplingRulesFile
        + ", profilingAgentless="
        + profilingAgentless
        + ", profilingUrl='"
        + profilingUrl
        + '\''
        + ", profilingTags="
        + profilingTags
        + ", profilingStartDelay="
        + profilingStartDelay
        + ", profilingStartForceFirst="
        + profilingStartForceFirst
        + ", profilingUploadPeriod="
        + profilingUploadPeriod
        + ", profilingTemplateOverrideFile='"
        + profilingTemplateOverrideFile
        + '\''
        + ", profilingUploadTimeout="
        + profilingUploadTimeout
        + ", profilingUploadCompression='"
        + profilingUploadCompression
        + '\''
        + ", profilingProxyHost='"
        + profilingProxyHost
        + '\''
        + ", profilingProxyPort="
        + profilingProxyPort
        + ", profilingProxyUsername='"
        + profilingProxyUsername
        + '\''
        + ", profilingProxyPassword="
        + (profilingProxyPassword == null ? "null" : "****")
        + ", profilingExceptionSampleLimit="
        + profilingExceptionSampleLimit
        + ", profilingExceptionHistogramTopItems="
        + profilingExceptionHistogramTopItems
        + ", profilingExceptionHistogramMaxCollectionSize="
        + profilingExceptionHistogramMaxCollectionSize
        + ", profilingExcludeAgentThreads="
        + profilingExcludeAgentThreads
        + ", crashTrackingTags="
        + crashTrackingTags
        + ", crashTrackingAgentless="
        + crashTrackingAgentless
        + ", remoteConfigEnabled="
        + remoteConfigEnabled
        + ", remoteConfigUrl="
        + remoteConfigUrl
        + ", remoteConfigInitialPollInterval="
        + remoteConfigInitialPollInterval
        + ", remoteConfigMaxPayloadSize="
        + remoteConfigMaxPayloadSize
        + ", remoteConfigIntegrityCheckEnabled="
        + remoteConfigIntegrityCheckEnabled
        + ", debuggerEnabled="
        + debuggerEnabled
        + ", debuggerUploadTimeout="
        + debuggerUploadTimeout
        + ", debuggerUploadFlushInterval="
        + debuggerUploadFlushInterval
        + ", debuggerClassFileDumpEnabled="
        + debuggerClassFileDumpEnabled
        + ", debuggerPollInterval="
        + debuggerPollInterval
        + ", debuggerDiagnosticsInterval="
        + debuggerDiagnosticsInterval
        + ", debuggerMetricEnabled="
        + debuggerMetricEnabled
        + ", debuggerProbeFileLocation="
        + debuggerProbeFileLocation
        + ", debuggerUploadBatchSize="
        + debuggerUploadBatchSize
        + ", debuggerMaxPayloadSize="
        + debuggerMaxPayloadSize
        + ", debuggerVerifyByteCode="
        + debuggerVerifyByteCode
        + ", debuggerInstrumentTheWorld="
        + debuggerInstrumentTheWorld
        + ", debuggerExcludeFile="
        + debuggerExcludeFile
        + ", awsPropagationEnabled="
        + awsPropagationEnabled
        + ", sqsPropagationEnabled="
        + sqsPropagationEnabled
        + ", kafkaClientPropagationEnabled="
        + kafkaClientPropagationEnabled
        + ", kafkaClientPropagationDisabledTopics="
        + kafkaClientPropagationDisabledTopics
        + ", kafkaClientBase64DecodingEnabled="
        + kafkaClientBase64DecodingEnabled
        + ", jmsPropagationEnabled="
        + jmsPropagationEnabled
        + ", jmsPropagationDisabledTopics="
        + jmsPropagationDisabledTopics
        + ", jmsPropagationDisabledQueues="
        + jmsPropagationDisabledQueues
        + ", rabbitPropagationEnabled="
        + rabbitPropagationEnabled
        + ", rabbitPropagationDisabledQueues="
        + rabbitPropagationDisabledQueues
        + ", rabbitPropagationDisabledExchanges="
        + rabbitPropagationDisabledExchanges
        + ", messageBrokerSplitByDestination="
        + messageBrokerSplitByDestination
        + ", hystrixTagsEnabled="
        + hystrixTagsEnabled
        + ", hystrixMeasuredEnabled="
        + hystrixMeasuredEnabled
        + ", igniteCacheIncludeKeys="
        + igniteCacheIncludeKeys
        + ", servletPrincipalEnabled="
        + servletPrincipalEnabled
        + ", servletAsyncTimeoutError="
        + servletAsyncTimeoutError
        + ", datadogTagsLimit="
        + xDatadogTagsMaxLength
        + ", traceAgentV05Enabled="
        + traceAgentV05Enabled
        + ", debugEnabled="
        + debugEnabled
        + ", configFile='"
        + configFileStatus
        + '\''
        + ", idGenerationStrategy="
        + idGenerationStrategy
        + ", grpcIgnoredInboundMethods="
        + grpcIgnoredInboundMethods
        + ", grpcIgnoredOutboundMethods="
        + grpcIgnoredOutboundMethods
        + ", grpcServerErrorStatuses="
        + grpcServerErrorStatuses
        + ", grpcClientErrorStatuses="
        + grpcClientErrorStatuses
        + ", clientIpEnabled="
        + clientIpEnabled
        + ", appSecReportingInband="
        + appSecReportingInband
        + ", appSecRulesFile='"
        + appSecRulesFile
        + "'"
        + ", appSecHttpBlockedTemplateHtml="
        + appSecHttpBlockedTemplateHtml
        + ", appSecWafTimeout="
        + appSecWafTimeout
        + " us, appSecHttpBlockedTemplateJson="
        + appSecHttpBlockedTemplateJson
        + ", cwsEnabled="
        + cwsEnabled
        + ", cwsTlsRefresh="
        + cwsTlsRefresh
        + '}';
  }
}
