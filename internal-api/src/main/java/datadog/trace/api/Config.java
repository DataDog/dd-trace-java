package datadog.trace.api;

import static datadog.environment.JavaVirtualMachine.isJavaVersion;
import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.trace.api.ConfigDefaults.DEFAULT_ADD_SPAN_POINTERS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_WRITER_TYPE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_API_SECURITY_DOWNSTREAM_REQUEST_ANALYSIS_SAMPLE_RATE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_API_SECURITY_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_API_SECURITY_ENDPOINT_COLLECTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_API_SECURITY_ENDPOINT_COLLECTION_MESSAGE_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_API_SECURITY_MAX_DOWNSTREAM_REQUEST_BODY_ANALYSIS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_API_SECURITY_SAMPLE_DELAY;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_BODY_PARSING_SIZE_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_MAX_STACK_TRACES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_MAX_STACK_TRACE_DEPTH;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_RASP_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_REPORTING_INBAND;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_STACK_TRACE_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_TRACE_RATE_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_WAF_METRICS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_WAF_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CASSANDRA_KEYSPACE_STATEMENT_EXTRACTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_AGENTLESS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_AUTO_CONFIGURATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_BACKEND_API_TIMEOUT_MILLIS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_COMPILER_PLUGIN_VERSION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_GIT_REMOTE_NAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_GIT_UNSHALLOW_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_GIT_UPLOAD_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_GIT_UPLOAD_TIMEOUT_MILLIS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_JACOCO_PLUGIN_EXCLUDES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_JACOCO_PLUGIN_VERSION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_RESOURCE_FOLDER_NAMES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_SIGNAL_SERVER_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_SIGNAL_SERVER_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_SOURCE_DATA_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CLIENT_IP_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CLOCK_SYNC_PERIOD;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CODE_ORIGIN_FOR_SPANS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CODE_ORIGIN_MAX_USER_FRAMES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_COUCHBASE_INTERNAL_SPANS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CWS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CWS_TLS_REFRESH;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DATA_JOBS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DATA_JOBS_OPENLINEAGE_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DATA_JOBS_OPENLINEAGE_TIMEOUT_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DATA_STREAMS_BUCKET_DURATION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DATA_STREAMS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_DBM_PROPAGATION_MODE_MODE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_DBM_TRACE_PREPARED_STATEMENTS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_EXCEPTION_CAPTURE_INTERMEDIATE_SPANS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_EXCEPTION_CAPTURE_INTERVAL_SECONDS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_EXCEPTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_EXCEPTION_MAX_CAPTURED_FRAMES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_EXCEPTION_ONLY_LOCAL_ROOT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_MAX_EXCEPTION_PER_SECOND;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DEBUGGER_SOURCE_FILE_TRACKING_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DISTRIBUTED_DEBUGGER_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DOGSTATSD_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DOGSTATSD_START_DELAY;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_CAPTURE_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_LOCALVAR_HOISTING_LEVEL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_POLL_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_ELASTICSEARCH_BODY_AND_PARAMS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_ELASTICSEARCH_BODY_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_ELASTICSEARCH_PARAMS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_EXPERIMENTATAL_JEE_SPLIT_BY_DEPLOYMENT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_GRPC_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_GRPC_SERVER_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HEALTH_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_ANONYMOUS_CLASSES_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_DB_ROWS_TO_TAINT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_DEBUG_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_HARDCODED_SECRET_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_REDACTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_REDACTION_NAME_PATTERN;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_REDACTION_VALUE_PATTERN;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_STACKTRACE_LEAK_SUPPRESS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_STACK_TRACE_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_TRUNCATION_MAX_VALUE_LENGTH;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_WEAK_HASH_ALGORITHMS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_INSTRUMENTATION_SOURCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JAX_RS_EXCEPTION_AS_ERROR_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_LLM_OBS_AGENTLESS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_LOGS_INJECTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PERF_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITY_SAMPLING_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITY_SAMPLING_FORCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_STYLE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_MAX_EXTRA_SERVICES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_POLL_INTERVAL_SECONDS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_TARGETS_KEY;
import static datadog.trace.api.ConfigDefaults.DEFAULT_REMOTE_CONFIG_TARGETS_KEY_ID;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RUM_MAJOR_VERSION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SCOPE_DEPTH_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SCOPE_ITERATION_KEEP_ALIVE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SECURE_RANDOM;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_DISCOVERY_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SITE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SPARK_APP_NAME_AS_SERVICE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SPARK_TASK_HISTOGRAM_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SSI_INJECTION_FORCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_STARTUP_LOGS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SYMBOL_DATABASE_COMPRESSED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SYMBOL_DATABASE_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SYMBOL_DATABASE_FLUSH_THRESHOLD;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SYMBOL_DATABASE_FORCE_UPLOAD;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_DEBUG_REQUESTS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_DEPENDENCY_COLLECTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_DEPENDENCY_RESOLUTION_QUEUE_SIZE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_LOG_COLLECTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_METRICS_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_128_BIT_TRACEID_GENERATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_128_BIT_TRACEID_LOGGING_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_V05_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANALYTICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_BAGGAGE_MAX_BYTES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_BAGGAGE_MAX_ITEMS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_BAGGAGE_TAG_KEYS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_CLOUD_PAYLOAD_TAGGING_SERVICES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_EXPERIMENTAL_FEATURES_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_KEEP_LATENCY_THRESHOLD_MS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_LONG_RUNNING_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_POST_PROCESSING_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_PROPAGATION_BEHAVIOR_EXTRACT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_PROPAGATION_EXTRACT_FIRST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_PROPAGATION_STYLE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_RATE_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_REPORT_HOSTNAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_RESOLVER_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH;
import static datadog.trace.api.ConfigDefaults.DEFAULT_WEBSOCKET_MESSAGES_INHERIT_SAMPLING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_WEBSOCKET_MESSAGES_SEPARATE_TRACES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_WEBSOCKET_TAG_SESSION_ID;
import static datadog.trace.api.ConfigDefaults.DEFAULT_WRITER_BAGGAGE_INJECT;
import static datadog.trace.api.ConfigSetting.NON_DEFAULT_SEQ_ID;
import static datadog.trace.api.DDTags.APM_ENABLED;
import static datadog.trace.api.DDTags.HOST_TAG;
import static datadog.trace.api.DDTags.INTERNAL_HOST_NAME;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.PID_TAG;
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;
import static datadog.trace.api.DDTags.RUNTIME_VERSION_TAG;
import static datadog.trace.api.DDTags.SCHEMA_VERSION_TAG_KEY;
import static datadog.trace.api.DDTags.SERVICE;
import static datadog.trace.api.DDTags.SERVICE_TAG;
import static datadog.trace.api.config.AIGuardConfig.AI_GUARD_ENABLED;
import static datadog.trace.api.config.AIGuardConfig.AI_GUARD_ENDPOINT;
import static datadog.trace.api.config.AIGuardConfig.AI_GUARD_MAX_CONTENT_SIZE;
import static datadog.trace.api.config.AIGuardConfig.AI_GUARD_MAX_MESSAGES_LENGTH;
import static datadog.trace.api.config.AIGuardConfig.AI_GUARD_TIMEOUT;
import static datadog.trace.api.config.AIGuardConfig.DEFAULT_AI_GUARD_ENABLED;
import static datadog.trace.api.config.AIGuardConfig.DEFAULT_AI_GUARD_MAX_CONTENT_SIZE;
import static datadog.trace.api.config.AIGuardConfig.DEFAULT_AI_GUARD_MAX_MESSAGES_LENGTH;
import static datadog.trace.api.config.AIGuardConfig.DEFAULT_AI_GUARD_TIMEOUT;
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_DOWNSTREAM_REQUEST_ANALYSIS_SAMPLE_RATE;
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_ENABLED;
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_ENABLED_EXPERIMENTAL;
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_ENDPOINT_COLLECTION_ENABLED;
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_ENDPOINT_COLLECTION_MESSAGE_LIMIT;
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_MAX_DOWNSTREAM_REQUEST_BODY_ANALYSIS;
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_SAMPLE_DELAY;
import static datadog.trace.api.config.AppSecConfig.APPSEC_AUTOMATED_USER_EVENTS_TRACKING;
import static datadog.trace.api.config.AppSecConfig.APPSEC_AUTO_USER_INSTRUMENTATION_MODE;
import static datadog.trace.api.config.AppSecConfig.APPSEC_BODY_PARSING_SIZE_LIMIT;
import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_HTML;
import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_JSON;
import static datadog.trace.api.config.AppSecConfig.APPSEC_IP_ADDR_HEADER;
import static datadog.trace.api.config.AppSecConfig.APPSEC_MAX_STACKTRACES_DEPRECATED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_MAX_STACKTRACE_DEPTH_DEPRECATED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_MAX_STACK_TRACES;
import static datadog.trace.api.config.AppSecConfig.APPSEC_MAX_STACK_TRACE_DEPTH;
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP;
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP;
import static datadog.trace.api.config.AppSecConfig.APPSEC_RASP_ENABLED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_REPORTING_INBAND;
import static datadog.trace.api.config.AppSecConfig.APPSEC_REPORT_TIMEOUT_SEC;
import static datadog.trace.api.config.AppSecConfig.APPSEC_RULES_FILE;
import static datadog.trace.api.config.AppSecConfig.APPSEC_SCA_ENABLED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_STACKTRACE_ENABLED_DEPRECATED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_STACK_TRACE_ENABLED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_TRACE_RATE_LIMIT;
import static datadog.trace.api.config.AppSecConfig.APPSEC_WAF_METRICS;
import static datadog.trace.api.config.AppSecConfig.APPSEC_WAF_TIMEOUT;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ADDITIONAL_CHILD_PROCESS_JVM_ARGS;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AGENT_JAR_URI;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AUTO_CONFIGURATION_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AUTO_INSTRUMENTATION_PROVIDER;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_BACKEND_API_TIMEOUT_MILLIS;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_EXCLUDES;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_INCLUDES;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_LINES_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_REPORT_DUMP_DIR;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_REPORT_UPLOAD_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_ROOT_PACKAGES_LIMIT;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_COMPILER_PLUGIN_VERSION;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_DEBUG_PORT;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_EARLY_FLAKE_DETECTION_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_EARLY_FLAKE_DETECTION_LOWER_LIMIT;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_EXECUTION_SETTINGS_CACHE_SIZE;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_COUNT;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_ONLY_KNOWN_FLAKES;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_GIT_CLIENT_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_GIT_REMOTE_NAME;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_GIT_UNSHALLOW_DEFER;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_GIT_UNSHALLOW_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_GIT_UPLOAD_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_GIT_UPLOAD_TIMEOUT_MILLIS;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_GRADLE_SOURCE_SETS;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_IMPACTED_TESTS_DETECTION_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_INJECTED_TRACER_VERSION;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_INTAKE_AGENTLESS_URL;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ITR_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_VERSION;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_JVM_INFO_CACHE_SIZE;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_KNOWN_TESTS_REQUEST_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_MODULE_NAME;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_REPO_INDEX_DUPLICATE_KEY_CHECK_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_REPO_INDEX_FOLLOW_SYMLINKS;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_RESOURCE_FOLDER_NAMES;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_RUM_FLUSH_WAIT_MILLIS;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_SCALATEST_FORK_MONITOR_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_SIGNAL_CLIENT_TIMEOUT_MILLIS;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_HOST;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_PORT;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_SOURCE_DATA_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_TELEMETRY_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_TEST_COMMAND;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_TEST_ORDER;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_TEST_SKIPPING_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_TOTAL_FLAKY_RETRY_COUNT;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_TRACE_SANITATION_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.GIT_COMMIT_HEAD_SHA;
import static datadog.trace.api.config.CiVisibilityConfig.GIT_PULL_REQUEST_BASE_BRANCH;
import static datadog.trace.api.config.CiVisibilityConfig.GIT_PULL_REQUEST_BASE_BRANCH_SHA;
import static datadog.trace.api.config.CiVisibilityConfig.TEST_FAILED_TEST_REPLAY_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.TEST_MANAGEMENT_ATTEMPT_TO_FIX_RETRIES;
import static datadog.trace.api.config.CiVisibilityConfig.TEST_MANAGEMENT_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.TEST_SESSION_NAME;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_AGENTLESS;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_AGENTLESS_DEFAULT;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_TAGS;
import static datadog.trace.api.config.CwsConfig.CWS_ENABLED;
import static datadog.trace.api.config.CwsConfig.CWS_TLS_REFRESH;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_EXCEPTION_CAPTURE_INTERMEDIATE_SPANS_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_EXCEPTION_CAPTURE_INTERVAL_SECONDS;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_EXCEPTION_CAPTURE_MAX_FRAMES;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_EXCEPTION_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_EXCEPTION_MAX_CAPTURED_FRAMES;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_EXCEPTION_ONLY_LOCAL_ROOT;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_MAX_EXCEPTION_PER_SECOND;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_SOURCE_FILE_TRACKING_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DISTRIBUTED_DEBUGGER_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_CAPTURE_TIMEOUT;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_EXCLUDE_FILES;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_INCLUDE_FILES;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_INSTRUMENT_THE_WORLD;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_LOCALVAR_HOISTING_LEVEL;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_METRICS_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_POLL_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_PROBE_FILE;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_REDACTED_IDENTIFIERS;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_REDACTED_TYPES;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_REDACTION_EXCLUDED_IDENTIFIERS;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_SNAPSHOT_URL;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_INTERVAL_SECONDS;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.DebuggerConfig.DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE;
import static datadog.trace.api.config.DebuggerConfig.EXCEPTION_REPLAY_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.SYMBOL_DATABASE_COMPRESSED;
import static datadog.trace.api.config.DebuggerConfig.SYMBOL_DATABASE_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.SYMBOL_DATABASE_FLUSH_THRESHOLD;
import static datadog.trace.api.config.DebuggerConfig.SYMBOL_DATABASE_FORCE_UPLOAD;
import static datadog.trace.api.config.DebuggerConfig.THIRD_PARTY_EXCLUDES;
import static datadog.trace.api.config.DebuggerConfig.THIRD_PARTY_INCLUDES;
import static datadog.trace.api.config.DebuggerConfig.THIRD_PARTY_SHADING_IDENTIFIERS;
import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_ENABLED;
import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_LEVEL;
import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_QUEUE_SIZE;
import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_URL;
import static datadog.trace.api.config.GeneralConfig.API_KEY;
import static datadog.trace.api.config.GeneralConfig.API_KEY_FILE;
import static datadog.trace.api.config.GeneralConfig.APPLICATION_KEY;
import static datadog.trace.api.config.GeneralConfig.APPLICATION_KEY_FILE;
import static datadog.trace.api.config.GeneralConfig.APP_KEY;
import static datadog.trace.api.config.GeneralConfig.AZURE_APP_SERVICES;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_OPENLINEAGE_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_OPENLINEAGE_TIMEOUT_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_BUCKET_DURATION_SECONDS;
import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_ARGS;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_HOST;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_NAMED_PIPE;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_PATH;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_PORT;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_START_DELAY;
import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.GLOBAL_TAGS;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_HOST;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_PORT;
import static datadog.trace.api.config.GeneralConfig.INSTRUMENTATION_SOURCE;
import static datadog.trace.api.config.GeneralConfig.JDK_SOCKET_ENABLED;
import static datadog.trace.api.config.GeneralConfig.LOG_LEVEL;
import static datadog.trace.api.config.GeneralConfig.PERF_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.PRIMARY_TAG;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_ID_ENABLED;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_RUNTIME_ID_ENABLED;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME_SET_BY_USER;
import static datadog.trace.api.config.GeneralConfig.SITE;
import static datadog.trace.api.config.GeneralConfig.SSI_INJECTION_ENABLED;
import static datadog.trace.api.config.GeneralConfig.SSI_INJECTION_FORCE;
import static datadog.trace.api.config.GeneralConfig.STACK_TRACE_LENGTH_LIMIT;
import static datadog.trace.api.config.GeneralConfig.STARTUP_LOGS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.STATSD_CLIENT_QUEUE_SIZE;
import static datadog.trace.api.config.GeneralConfig.STATSD_CLIENT_SOCKET_BUFFER;
import static datadog.trace.api.config.GeneralConfig.STATSD_CLIENT_SOCKET_TIMEOUT;
import static datadog.trace.api.config.GeneralConfig.TAGS;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_DEBUG_REQUESTS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_DEPENDENCY_COLLECTION_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_DEPENDENCY_RESOLUTION_QUEUE_SIZE;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_LOG_COLLECTION_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_METRICS_INTERVAL;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_BUFFERING_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_IGNORED_RESOURCES;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_MAX_AGGREGATES;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_MAX_PENDING;
import static datadog.trace.api.config.GeneralConfig.TRACE_DEBUG;
import static datadog.trace.api.config.GeneralConfig.TRACE_STATS_COMPUTATION_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACE_TAGS;
import static datadog.trace.api.config.GeneralConfig.TRACE_TRIAGE;
import static datadog.trace.api.config.GeneralConfig.TRIAGE_REPORT_DIR;
import static datadog.trace.api.config.GeneralConfig.TRIAGE_REPORT_TRIGGER;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.api.config.IastConfig.IAST_ANONYMOUS_CLASSES_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_CONTEXT_MODE;
import static datadog.trace.api.config.IastConfig.IAST_DB_ROWS_TO_TAINT;
import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE;
import static datadog.trace.api.config.IastConfig.IAST_EXPERIMENTAL_PROPAGATION_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_HARDCODED_SECRET_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_REDACTION_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_REDACTION_NAME_PATTERN;
import static datadog.trace.api.config.IastConfig.IAST_REDACTION_VALUE_PATTERN;
import static datadog.trace.api.config.IastConfig.IAST_SECURITY_CONTROLS_CONFIGURATION;
import static datadog.trace.api.config.IastConfig.IAST_SOURCE_MAPPING_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_SOURCE_MAPPING_MAX_SIZE;
import static datadog.trace.api.config.IastConfig.IAST_STACKTRACE_ENABLED_DEPRECATED;
import static datadog.trace.api.config.IastConfig.IAST_STACKTRACE_LEAK_SUPPRESS_DEPRECATED;
import static datadog.trace.api.config.IastConfig.IAST_STACK_TRACE_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_STACK_TRACE_LEAK_SUPPRESS;
import static datadog.trace.api.config.IastConfig.IAST_TELEMETRY_VERBOSITY;
import static datadog.trace.api.config.IastConfig.IAST_TRUNCATION_MAX_VALUE_LENGTH;
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
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_AGENTLESS_ENABLED;
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_ML_APP;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AGENTLESS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AGENTLESS_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_VERY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_VERY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_BACKPRESSURE_SAMPLE_LIMIT_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_BACKPRESSURE_SAMPLING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_BACKPRESSURE_SAMPLING_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DEBUG_UPLOAD_COMPRESSION;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DEBUG_UPLOAD_COMPRESSION_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_RECORD_MESSAGE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_RECORD_MESSAGE_DEFAULT;
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
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TIMELINE_EVENTS_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TIMELINE_EVENTS_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_COMPRESSION;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_PERIOD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_PERIOD_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_SUMMARY_ON_413;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_SUMMARY_ON_413_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_TIMEOUT_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_URL;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_ENABLED;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_MAX_EXTRA_SERVICES;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_POLL_INTERVAL_SECONDS;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_TARGETS_KEY;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_TARGETS_KEY_ID;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIG_URL;
import static datadog.trace.api.config.RumConfig.RUM_APPLICATION_ID;
import static datadog.trace.api.config.RumConfig.RUM_CLIENT_TOKEN;
import static datadog.trace.api.config.RumConfig.RUM_DEFAULT_PRIVACY_LEVEL;
import static datadog.trace.api.config.RumConfig.RUM_ENVIRONMENT;
import static datadog.trace.api.config.RumConfig.RUM_MAJOR_VERSION;
import static datadog.trace.api.config.RumConfig.RUM_REMOTE_CONFIGURATION_ID;
import static datadog.trace.api.config.RumConfig.RUM_SERVICE;
import static datadog.trace.api.config.RumConfig.RUM_SESSION_REPLAY_SAMPLE_RATE;
import static datadog.trace.api.config.RumConfig.RUM_SESSION_SAMPLE_RATE;
import static datadog.trace.api.config.RumConfig.RUM_SITE;
import static datadog.trace.api.config.RumConfig.RUM_TRACK_LONG_TASKS;
import static datadog.trace.api.config.RumConfig.RUM_TRACK_RESOURCES;
import static datadog.trace.api.config.RumConfig.RUM_TRACK_USER_INTERACTION;
import static datadog.trace.api.config.RumConfig.RUM_VERSION;
import static datadog.trace.api.config.TraceInstrumentationConfig.ADD_SPAN_POINTERS;
import static datadog.trace.api.config.TraceInstrumentationConfig.AXIS_PROMOTE_RESOURCE_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.CASSANDRA_KEYSPACE_STATEMENT_EXTRACTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.CODE_ORIGIN_FOR_SPANS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.CODE_ORIGIN_MAX_USER_FRAMES;
import static datadog.trace.api.config.TraceInstrumentationConfig.COUCHBASE_INTERNAL_SPANS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_HOST;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_INJECT_SQL_BASEHASH;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_TRACE_PREPARED_STATEMENTS;
import static datadog.trace.api.config.TraceInstrumentationConfig.ELASTICSEARCH_BODY_AND_PARAMS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.ELASTICSEARCH_BODY_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.ELASTICSEARCH_PARAMS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.EXPERIMENTATAL_JEE_SPLIT_BY_DEPLOYMENT;
import static datadog.trace.api.config.TraceInstrumentationConfig.GOOGLE_PUBSUB_IGNORED_GRPC_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_IGNORED_INBOUND_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_IGNORED_OUTBOUND_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_SERVER_ERROR_STATUSES;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_SERVER_TRIM_PACKAGE_RESOURCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_TAG_HEADERS;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_DECODED_RESOURCE_PRESERVE_SPACES;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_RESOURCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_ROUTE_BASED_NAMING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HYSTRIX_MEASURED_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.HYSTRIX_TAGS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.IGNITE_CACHE_INCLUDE_KEYS;
import static datadog.trace.api.config.TraceInstrumentationConfig.INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.JAX_RS_EXCEPTION_AS_ERROR_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES;
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS;
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_UNACKNOWLEDGED_MAX_AGE;
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_BASE64_DECODING_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.MESSAGE_BROKER_SPLIT_BY_DESTINATION;
import static datadog.trace.api.config.TraceInstrumentationConfig.OBFUSCATION_QUERY_STRING_REGEXP;
import static datadog.trace.api.config.TraceInstrumentationConfig.PLAY_REPORT_HTTP_STATUS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_INCLUDE_ROUTINGKEY_IN_RESOURCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_EXCHANGES;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_QUEUES;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESILIENCE4J_MEASURED_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESILIENCE4J_TAG_METRICS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_ASYNC_TIMEOUT_ERROR;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_PRINCIPAL_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_ROOT_CONTEXT_SERVICE_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.SPARK_APP_NAME_AS_SERVICE;
import static datadog.trace.api.config.TraceInstrumentationConfig.SPARK_TASK_HISTOGRAM_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.SPRING_DATA_REPOSITORY_INTERFACE_RESOURCE_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.SQS_BODY_PROPAGATION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_128_BIT_TRACEID_LOGGING_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_WEBSOCKET_MESSAGES_INHERIT_SAMPLING;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_WEBSOCKET_MESSAGES_SEPARATE_TRACES;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_WEBSOCKET_TAG_SESSION_ID;
import static datadog.trace.api.config.TracerConfig.AGENT_HOST;
import static datadog.trace.api.config.TracerConfig.AGENT_NAMED_PIPE;
import static datadog.trace.api.config.TracerConfig.AGENT_PORT_LEGACY;
import static datadog.trace.api.config.TracerConfig.AGENT_TIMEOUT;
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET;
import static datadog.trace.api.config.TracerConfig.BAGGAGE_MAPPING;
import static datadog.trace.api.config.TracerConfig.CLIENT_IP_ENABLED;
import static datadog.trace.api.config.TracerConfig.CLOCK_SYNC_PERIOD;
import static datadog.trace.api.config.TracerConfig.ENABLE_TRACE_AGENT_V05;
import static datadog.trace.api.config.TracerConfig.FORCE_CLEAR_TEXT_HTTP_FOR_INTAKE_CLIENT;
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.ID_GENERATION_STRATEGY;
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_ENABLED;
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING_FORCE;
import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.config.TracerConfig.PROPAGATION_STYLE_EXTRACT;
import static datadog.trace.api.config.TracerConfig.PROPAGATION_STYLE_INJECT;
import static datadog.trace.api.config.TracerConfig.PROXY_NO_PROXY;
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS_COMMA_ALLOWED;
import static datadog.trace.api.config.TracerConfig.RESPONSE_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.SAMPLING_MECHANISM_VALIDATION_DISABLED;
import static datadog.trace.api.config.TracerConfig.SCOPE_DEPTH_LIMIT;
import static datadog.trace.api.config.TracerConfig.SCOPE_ITERATION_KEEP_ALIVE;
import static datadog.trace.api.config.TracerConfig.SCOPE_STRICT_MODE;
import static datadog.trace.api.config.TracerConfig.SECURE_RANDOM;
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING;
import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES;
import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES_FILE;
import static datadog.trace.api.config.TracerConfig.SPAN_TAGS;
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static datadog.trace.api.config.TracerConfig.TRACE_128_BIT_TRACEID_GENERATION_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_ARGS;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PATH;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PORT;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_URL;
import static datadog.trace.api.config.TracerConfig.TRACE_ANALYTICS_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_BAGGAGE_MAX_BYTES;
import static datadog.trace.api.config.TracerConfig.TRACE_BAGGAGE_MAX_ITEMS;
import static datadog.trace.api.config.TracerConfig.TRACE_BAGGAGE_TAG_KEYS;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_HEADER;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_CLOUD_PAYLOAD_TAGGING_MAX_DEPTH;
import static datadog.trace.api.config.TracerConfig.TRACE_CLOUD_PAYLOAD_TAGGING_MAX_TAGS;
import static datadog.trace.api.config.TracerConfig.TRACE_CLOUD_PAYLOAD_TAGGING_SERVICES;
import static datadog.trace.api.config.TracerConfig.TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING;
import static datadog.trace.api.config.TracerConfig.TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING;
import static datadog.trace.api.config.TracerConfig.TRACE_EXPERIMENTAL_FEATURES_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_GIT_METADATA_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_INFERRED_PROXY_SERVICES_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_KEEP_LATENCY_THRESHOLD_MS;
import static datadog.trace.api.config.TracerConfig.TRACE_LONG_RUNNING_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_LONG_RUNNING_FLUSH_INTERVAL;
import static datadog.trace.api.config.TracerConfig.TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL;
import static datadog.trace.api.config.TracerConfig.TRACE_PEER_HOSTNAME_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_PEER_SERVICE_COMPONENT_OVERRIDES;
import static datadog.trace.api.config.TracerConfig.TRACE_PEER_SERVICE_DEFAULTS_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_PEER_SERVICE_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_POST_PROCESSING_TIMEOUT;
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_BEHAVIOR_EXTRACT;
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_EXTRACT_FIRST;
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_STYLE;
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_STYLE_EXTRACT;
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_STYLE_INJECT;
import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_REPORT_HOSTNAME;
import static datadog.trace.api.config.TracerConfig.TRACE_RESOLVER_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_OPERATION_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SERVICE_DISCOVERY_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA;
import static datadog.trace.api.config.TracerConfig.TRACE_STRICT_WRITES_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_X_DATADOG_TAGS_MAX_LENGTH;
import static datadog.trace.api.config.TracerConfig.WRITER_BAGGAGE_INJECT;
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE;
import static datadog.trace.api.iast.IastDetectionMode.DEFAULT;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;
import static datadog.trace.util.ConfigStrings.propertyNameToEnvironmentVariableName;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.environment.SystemProperties;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.IastDetectionMode;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.api.intake.Intake;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.api.profiling.ProfilingEnablement;
import datadog.trace.api.rum.RumInjectorConfig;
import datadog.trace.api.rum.RumInjectorConfig.PrivacyLevel;
import datadog.trace.api.telemetry.ConfigInversionMetricCollectorImpl;
import datadog.trace.api.telemetry.ConfigInversionMetricCollectorProvider;
import datadog.trace.api.telemetry.OtelEnvMetricCollectorImpl;
import datadog.trace.api.telemetry.OtelEnvMetricCollectorProvider;
import datadog.trace.bootstrap.config.provider.CapturedEnvironmentConfigSource;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.config.provider.SystemPropertiesConfigSource;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.context.TraceScope;
import datadog.trace.util.ConfigStrings;
import datadog.trace.util.PidHelper;
import datadog.trace.util.RandomUtils;
import datadog.trace.util.Strings;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import java.io.BufferedReader;
import java.io.File;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config reads values with the following priority:
 *
 * <p>1) system properties
 *
 * <p>2) environment variables,
 *
 * <p>3) optional configuration file
 *
 * <p>4) platform dependant properties. It also includes default values to ensure a valid config.
 *
 * <p>System properties are {@link Config#PREFIX}'ed. Environment variables are the same as the
 * system property, but uppercased and '.' is replaced with '_'.
 *
 * @see ConfigProvider for details on how configs are processed
 * @see InstrumenterConfig for pre-instrumentation configurations
 * @see DynamicConfig for configuration that can be dynamically updated via remote-config
 */
public class Config {

  private static final Logger log = LoggerFactory.getLogger(Config.class);

  private static final Pattern COLON = Pattern.compile(":");

  private final InstrumenterConfig instrumenterConfig;

  private final long startTimeMillis = System.currentTimeMillis();
  private final boolean timelineEventsEnabled;

  /**
   * this is a random UUID that gets generated on JVM start up and is attached to every root span
   * and every JMX metric that is sent out.
   */
  static class RuntimeIdHolder {
    static final String runtimeId = RandomUtils.randomUUID().toString();
  }

  static class HostNameHolder {
    static final String hostName = initHostName();

    public static String getHostName() {
      return hostName;
    }
  }

  private final boolean runtimeIdEnabled;

  /** This is the version of the runtime, ex: 1.8.0_332, 11.0.15, 17.0.3 */
  private final String runtimeVersion;

  private final String applicationKey;

  /**
   * Note: this has effect only on profiling site. Traces are sent to Datadog agent and are not
   * affected by this setting. If CI Visibility is used with agentless mode, api key is used when
   * sending data (including traces) to backend
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
  private final boolean injectBaggageAsTagsEnabled;
  private final boolean agentConfiguredUsingDefault;
  private final String agentUrl;
  private final String agentHost;
  private final int agentPort;
  private final String agentUnixDomainSocket;
  private final String agentNamedPipe;
  private final int agentTimeout;
  /** Should be set to {@code true} when running in agentless mode in a JVM without TLS */
  private final boolean forceClearTextHttpForIntakeClient;

  private final Set<String> noProxyHosts;
  private final boolean prioritySamplingEnabled;
  private final String prioritySamplingForce;
  private final boolean traceResolverEnabled;
  private final int spanAttributeSchemaVersion;
  private final boolean peerHostNameEnabled;
  private final boolean peerServiceDefaultsEnabled;
  private final Map<String, String> peerServiceComponentOverrides;
  private final boolean removeIntegrationServiceNamesEnabled;
  private final boolean experimentalPropagateProcessTagsEnabled;
  private final Map<String, String> peerServiceMapping;
  private final Map<String, String> serviceMapping;
  private final Map<String, String> tags;
  private final Map<String, String> spanTags;
  private final Map<String, String> jmxTags;
  private final Map<String, String> requestHeaderTags;
  private final Map<String, String> responseHeaderTags;
  private final Map<String, String> baggageMapping;
  private final boolean requestHeaderTagsCommaAllowed;
  private final BitSet httpServerErrorStatuses;
  private final BitSet httpClientErrorStatuses;
  private final boolean httpServerTagQueryString;
  private final boolean httpServerRawQueryString;
  private final boolean httpServerRawResource;
  private final boolean httpServerDecodedResourcePreserveSpaces;
  private final boolean httpServerRouteBasedNaming;
  private final Map<String, String> httpServerPathResourceNameMapping;
  private final Map<String, String> httpClientPathResourceNameMapping;
  private final boolean httpResourceRemoveTrailingSlash;
  private final boolean httpClientTagQueryString;
  private final boolean httpClientTagHeaders;
  private final boolean httpClientSplitByDomain;
  private final boolean dbClientSplitByInstance;
  private final boolean dbClientSplitByInstanceTypeSuffix;
  private final boolean dbClientSplitByHost;
  private final Set<String> splitByTags;
  private final boolean jeeSplitByDeployment;
  private final int scopeDepthLimit;
  private final boolean scopeStrictMode;
  private final int scopeIterationKeepAlive;
  private final int partialFlushMinSpans;
  private final int traceKeepLatencyThreshold;
  private final boolean traceKeepLatencyThresholdEnabled;
  private final boolean traceStrictWritesEnabled;
  private final boolean logExtractHeaderNames;
  private final Set<PropagationStyle> propagationStylesToExtract;
  private final Set<PropagationStyle> propagationStylesToInject;
  private final boolean tracePropagationStyleB3PaddingEnabled;
  private final Set<TracePropagationStyle> tracePropagationStylesToExtract;
  private final Set<TracePropagationStyle> tracePropagationStylesToInject;
  private final TracePropagationBehaviorExtract tracePropagationBehaviorExtract;
  private final boolean tracePropagationExtractFirst;
  private final int traceBaggageMaxItems;
  private final int traceBaggageMaxBytes;
  private final List<String> traceBaggageTagKeys;
  private final boolean traceInferredProxyEnabled;
  private final int clockSyncPeriod;
  private final boolean logsInjectionEnabled;

  private final String dogStatsDNamedPipe;
  private final int dogStatsDStartDelay;

  private final Integer statsDClientQueueSize;
  private final Integer statsDClientSocketBuffer;
  private final Integer statsDClientSocketTimeout;

  private final boolean runtimeMetricsEnabled;
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

  private final boolean traceGitMetadataEnabled;

  private final boolean ssiInjectionForce;
  private final String ssiInjectionEnabled;
  private final String instrumentationSource;

  private final Map<String, String> traceSamplingServiceRules;
  private final Map<String, String> traceSamplingOperationRules;
  private final String traceSamplingRules;
  private final Double traceSampleRate;
  private final int traceRateLimit;
  private final String spanSamplingRules;
  private final String spanSamplingRulesFile;

  private final ProfilingEnablement profilingEnabled;
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
  private final int profilingBackPressureSampleLimit;
  private final boolean profilingBackPressureEnabled;
  private final int profilingDirectAllocationSampleLimit;
  private final int profilingExceptionHistogramTopItems;
  private final int profilingExceptionHistogramMaxCollectionSize;
  private final boolean profilingExcludeAgentThreads;
  private final boolean profilingUploadSummaryOn413Enabled;
  private final boolean profilingRecordExceptionMessage;

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
  private final UserIdCollectionMode appSecUserIdCollectionMode;
  private final Boolean appSecScaEnabled;
  private final boolean appSecRaspEnabled;
  private final boolean appSecStackTraceEnabled;
  private final int appSecMaxStackTraces;
  private final int appSecMaxStackTraceDepth;
  private final int appSecBodyParsingSizeLimit;
  private final boolean apiSecurityEnabled;
  private final float apiSecuritySampleDelay;
  private final boolean apiSecurityEndpointCollectionEnabled;
  private final int apiSecurityEndpointCollectionMessageLimit;
  private final int apiSecurityMaxDownstreamRequestBodyAnalysis;
  private final double apiSecurityDownstreamRequestAnalysisSampleRate;

  private final IastDetectionMode iastDetectionMode;
  private final int iastMaxConcurrentRequests;
  private final int iastVulnerabilitiesPerRequest;
  private final float iastRequestSampling;
  private final boolean iastDebugEnabled;
  private final Verbosity iastTelemetryVerbosity;
  private final boolean iastRedactionEnabled;
  private final String iastRedactionNamePattern;
  private final String iastRedactionValuePattern;
  private final int iastMaxRangeCount;
  private final int iastTruncationMaxValueLength;
  private final boolean iastStacktraceLeakSuppress;
  private final IastContext.Mode iastContextMode;
  private final boolean iastHardcodedSecretEnabled;
  private final boolean iastAnonymousClassesEnabled;
  private final boolean iastSourceMappingEnabled;
  private final int iastSourceMappingMaxSize;
  private final boolean iastStackTraceEnabled;
  private final boolean iastExperimentalPropagationEnabled;
  private final String iastSecurityControlsConfiguration;
  private final int iastDbRowsToTaint;

  private final boolean llmObsAgentlessEnabled;
  private final String llmObsAgentlessUrl;
  private final String llmObsMlApp;

  private final boolean ciVisibilityTraceSanitationEnabled;
  private final boolean ciVisibilityAgentlessEnabled;
  private final String ciVisibilityAgentlessUrl;
  private final String ciVisibilityIntakeAgentlessUrl;

  private final boolean ciVisibilitySourceDataEnabled;
  private final boolean ciVisibilityBuildInstrumentationEnabled;
  private final String ciVisibilityAgentJarUri;
  private final boolean ciVisibilityAutoConfigurationEnabled;
  private final String ciVisibilityAdditionalChildProcessJvmArgs;
  private final boolean ciVisibilityCompilerPluginAutoConfigurationEnabled;
  private final boolean ciVisibilityCodeCoverageEnabled;
  private final Boolean ciVisibilityCoverageLinesEnabled;
  private final String ciVisibilityCodeCoverageReportDumpDir;
  private final String ciVisibilityCompilerPluginVersion;
  private final String ciVisibilityJacocoPluginVersion;
  private final boolean ciVisibilityJacocoPluginVersionProvided;
  private final List<String> ciVisibilityCodeCoverageIncludes;
  private final List<String> ciVisibilityCodeCoverageExcludes;
  private final String[] ciVisibilityCodeCoverageIncludedPackages;
  private final String[] ciVisibilityCodeCoverageExcludedPackages;
  private final List<String> ciVisibilityJacocoGradleSourceSets;
  private final boolean ciVisibilityCodeCoverageReportUploadEnabled;
  private final Integer ciVisibilityDebugPort;
  private final boolean ciVisibilityGitClientEnabled;
  private final boolean ciVisibilityGitUploadEnabled;
  private final boolean ciVisibilityGitUnshallowEnabled;
  private final boolean ciVisibilityGitUnshallowDefer;
  private final long ciVisibilityGitCommandTimeoutMillis;
  private final String ciVisibilityGitRemoteName;
  private final long ciVisibilityBackendApiTimeoutMillis;
  private final long ciVisibilityGitUploadTimeoutMillis;
  private final String ciVisibilitySignalServerHost;
  private final int ciVisibilitySignalServerPort;
  private final int ciVisibilitySignalClientTimeoutMillis;
  private final boolean ciVisibilityItrEnabled;
  private final boolean ciVisibilityTestSkippingEnabled;
  private final boolean ciVisibilityCiProviderIntegrationEnabled;
  private final boolean ciVisibilityRepoIndexDuplicateKeyCheckEnabled;
  private final boolean ciVisibilityRepoIndexFollowSymlinks;
  private final int ciVisibilityExecutionSettingsCacheSize;
  private final int ciVisibilityJvmInfoCacheSize;
  private final int ciVisibilityCoverageRootPackagesLimit;
  private final String ciVisibilityInjectedTracerVersion;
  private final List<String> ciVisibilityResourceFolderNames;
  private final boolean ciVisibilityFlakyRetryEnabled;
  private final boolean ciVisibilityImpactedTestsDetectionEnabled;
  private final boolean ciVisibilityKnownTestsRequestEnabled;
  private final boolean ciVisibilityFlakyRetryOnlyKnownFlakes;
  private final int ciVisibilityFlakyRetryCount;
  private final int ciVisibilityTotalFlakyRetryCount;
  private final boolean ciVisibilityEarlyFlakeDetectionEnabled;
  private final int ciVisibilityEarlyFlakeDetectionLowerLimit;
  private final String ciVisibilitySessionName;
  private final String ciVisibilityModuleName;
  private final String ciVisibilityTestCommand;
  private final boolean ciVisibilityTelemetryEnabled;
  private final long ciVisibilityRumFlushWaitMillis;
  private final boolean ciVisibilityAutoInjected;
  private final String ciVisibilityTestOrder;
  private final boolean ciVisibilityTestManagementEnabled;
  private final Integer ciVisibilityTestManagementAttemptToFixRetries;
  private final boolean ciVisibilityScalatestForkMonitorEnabled;
  private final String gitPullRequestBaseBranch;
  private final String gitPullRequestBaseBranchSha;
  private final String gitCommitHeadSha;
  private final boolean ciVisibilityFailedTestReplayEnabled;

  private final boolean remoteConfigEnabled;
  private final boolean remoteConfigIntegrityCheckEnabled;
  private final String remoteConfigUrl;
  private final float remoteConfigPollIntervalSeconds;
  private final long remoteConfigMaxPayloadSize;
  private final String remoteConfigTargetsKeyId;
  private final String remoteConfigTargetsKey;

  private final int remoteConfigMaxExtraServices;

  private final boolean dbmInjectSqlBaseHash;
  private final String dbmPropagationMode;
  private final boolean dbmTracePreparedStatements;

  private final boolean dynamicInstrumentationEnabled;
  private final String dynamicInstrumentationSnapshotUrl;
  private final int dynamicInstrumentationUploadTimeout;
  private final int dynamicInstrumentationUploadFlushInterval;
  private final boolean dynamicInstrumentationClassFileDumpEnabled;
  private final int dynamicInstrumentationPollInterval;
  private final int dynamicInstrumentationDiagnosticsInterval;
  private final boolean dynamicInstrumentationMetricEnabled;
  private final String dynamicInstrumentationProbeFile;
  private final int dynamicInstrumentationUploadBatchSize;
  private final long dynamicInstrumentationMaxPayloadSize;
  private final boolean dynamicInstrumentationVerifyByteCode;
  private final String dynamicInstrumentationInstrumentTheWorld;
  private final String dynamicInstrumentationExcludeFiles;
  private final String dynamicInstrumentationIncludeFiles;
  private final int dynamicInstrumentationCaptureTimeout;
  private final String dynamicInstrumentationRedactedIdentifiers;
  private final Set<String> dynamicInstrumentationRedactionExcludedIdentifiers;
  private final String dynamicInstrumentationRedactedTypes;
  private final int dynamicInstrumentationLocalVarHoistingLevel;
  private final boolean symbolDatabaseEnabled;
  private final boolean symbolDatabaseForceUpload;
  private final int symbolDatabaseFlushThreshold;
  private final boolean symbolDatabaseCompressed;
  private final boolean debuggerExceptionEnabled;
  private final int debuggerMaxExceptionPerSecond;
  @Deprecated private final boolean debuggerExceptionOnlyLocalRoot;
  private final boolean debuggerExceptionCaptureIntermediateSpansEnabled;
  private final int debuggerExceptionMaxCapturedFrames;
  private final int debuggerExceptionCaptureInterval;
  private final boolean debuggerCodeOriginEnabled;
  private final int debuggerCodeOriginMaxUserFrames;
  private final boolean distributedDebuggerEnabled;
  private final boolean debuggerSourceFileTrackingEnabled;

  private final Set<String> debuggerThirdPartyIncludes;
  private final Set<String> debuggerThirdPartyExcludes;
  private final Set<String> debuggerShadingIdentifiers;

  private final boolean awsPropagationEnabled;
  private final boolean sqsPropagationEnabled;
  private final boolean sqsBodyPropagationEnabled;

  private final boolean kafkaClientPropagationEnabled;
  private final Set<String> kafkaClientPropagationDisabledTopics;
  private final boolean kafkaClientBase64DecodingEnabled;

  private final boolean jmsPropagationEnabled;
  private final Set<String> jmsPropagationDisabledTopics;
  private final Set<String> jmsPropagationDisabledQueues;
  private final int jmsUnacknowledgedMaxAge;

  private final boolean rabbitPropagationEnabled;
  private final Set<String> rabbitPropagationDisabledQueues;
  private final Set<String> rabbitPropagationDisabledExchanges;

  private final boolean rabbitIncludeRoutingKeyInResource;

  private final boolean messageBrokerSplitByDestination;

  private final boolean hystrixTagsEnabled;
  private final boolean hystrixMeasuredEnabled;

  private final boolean resilience4jMeasuredEnabled;
  private final boolean resilience4jTagMetricsEnabled;

  private final boolean igniteCacheIncludeKeys;

  private final String obfuscationQueryRegexp;

  // TODO: remove at a future point.
  private final boolean playReportHttpStatus;

  private final boolean servletPrincipalEnabled;
  private final boolean servletAsyncTimeoutError;

  private final boolean springDataRepositoryInterfaceResourceName;

  private final int xDatadogTagsMaxLength;

  private final boolean traceAgentV05Enabled;

  private final String logLevel;
  private final boolean debugEnabled;
  private final boolean triageEnabled;
  private final String triageReportTrigger;
  private final String triageReportDir;

  private final boolean startupLogsEnabled;
  private final String configFileStatus;

  private final IdGenerationStrategy idGenerationStrategy;

  private final boolean secureRandom;

  private final boolean trace128bitTraceIdGenerationEnabled;
  private final boolean logs128bitTraceIdEnabled;

  private final Set<String> grpcIgnoredInboundMethods;
  private final Set<String> grpcIgnoredOutboundMethods;
  private final boolean grpcServerTrimPackageResource;
  private final BitSet grpcServerErrorStatuses;
  private final BitSet grpcClientErrorStatuses;

  private final boolean cwsEnabled;
  private final int cwsTlsRefresh;

  private final boolean dataJobsEnabled;
  private final boolean dataJobsOpenLineageEnabled;
  private final boolean dataJobsOpenLineageTimeoutEnabled;

  private final boolean dataStreamsEnabled;
  private final float dataStreamsBucketDurationSeconds;

  private final boolean serviceDiscoveryEnabled;

  private final Set<String> iastWeakHashAlgorithms;

  private final Pattern iastWeakCipherAlgorithms;

  private final boolean iastDeduplicationEnabled;

  private final float telemetryHeartbeatInterval;
  private final long telemetryExtendedHeartbeatInterval;
  private final float telemetryMetricsInterval;
  private final boolean isTelemetryDependencyServiceEnabled;
  private final boolean telemetryMetricsEnabled;
  private final boolean isTelemetryLogCollectionEnabled;
  private final int telemetryDependencyResolutionQueueSize;

  private final boolean azureAppServices;
  private final boolean azureFunctions;
  private final boolean awsServerless;
  private final String traceAgentPath;
  private final List<String> traceAgentArgs;
  private final String dogStatsDPath;
  private final List<String> dogStatsDArgs;
  private final int dogStatsDPort;

  private String env;
  private String version;
  private final String primaryTag;

  private final ConfigProvider configProvider;

  private final boolean longRunningTraceEnabled;
  private final long longRunningTraceInitialFlushInterval;
  private final long longRunningTraceFlushInterval;

  private final boolean cassandraKeyspaceStatementExtractionEnabled;
  private final boolean couchbaseInternalSpansEnabled;
  private final boolean elasticsearchBodyEnabled;
  private final boolean elasticsearchParamsEnabled;
  private final boolean elasticsearchBodyAndParamsEnabled;
  private final boolean sparkTaskHistogramEnabled;
  private final boolean sparkAppNameAsService;
  private final boolean jaxRsExceptionAsErrorsEnabled;
  private final boolean websocketMessagesInheritSampling;
  private final boolean websocketMessagesSeparateTraces;
  private final boolean websocketTagSessionId;
  private final boolean axisPromoteResourceName;
  private final float traceFlushIntervalSeconds;
  private final long tracePostProcessingTimeout;

  private final boolean telemetryDebugRequestsEnabled;

  private final boolean agentlessLogSubmissionEnabled;
  private final int agentlessLogSubmissionQueueSize;
  private final String agentlessLogSubmissionLevel;
  private final String agentlessLogSubmissionUrl;
  private final String agentlessLogSubmissionProduct;

  private final Set<String> cloudPayloadTaggingServices;
  @Nullable private final List<String> cloudRequestPayloadTagging;
  @Nullable private final List<String> cloudResponsePayloadTagging;
  private final int cloudPayloadTaggingMaxDepth;
  private final int cloudPayloadTaggingMaxTags;

  private final long dependecyResolutionPeriodMillis;

  private final boolean apmTracingEnabled;
  private final Set<String> experimentalFeaturesEnabled;

  private final boolean jdkSocketEnabled;

  private final boolean optimizedMapEnabled;
  private final boolean spanBuilderReuseEnabled;
  private final int tagNameUtf8CacheSize;
  private final int tagValueUtf8CacheSize;
  private final int stackTraceLengthLimit;

  private final RumInjectorConfig rumInjectorConfig;

  private final boolean aiGuardEnabled;
  private final String aiGuardEndpoint;
  private final int aiGuardTimeout;
  private final int aiGuardMaxMessagesLength;
  private final int aiGuardMaxContentSize;

  static {
    // Bind telemetry collector to config module before initializing ConfigProvider
    OtelEnvMetricCollectorProvider.register(OtelEnvMetricCollectorImpl.getInstance());
    ConfigInversionMetricCollectorProvider.register(
        ConfigInversionMetricCollectorImpl.getInstance());
  }

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
    runtimeIdEnabled =
        configProvider.getBoolean(RUNTIME_ID_ENABLED, true, RUNTIME_METRICS_RUNTIME_ID_ENABLED);
    runtimeVersion = SystemProperties.getOrDefault("java.version", "unknown");

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
        log.error(
            "Cannot read API key from file {}, skipping. Exception {}", apiKeyFile, e.getMessage());
      }
    }
    site = configProvider.getString(SITE, DEFAULT_SITE);

    String tmpApplicationKey =
        configProvider.getStringExcludingSource(
            APPLICATION_KEY, null, SystemPropertiesConfigSource.class, APP_KEY);
    String applicationKeyFile = configProvider.getString(APPLICATION_KEY_FILE);
    if (applicationKeyFile != null) {
      try {
        tmpApplicationKey =
            new String(Files.readAllBytes(Paths.get(applicationKeyFile)), StandardCharsets.UTF_8)
                .trim();
      } catch (final IOException e) {
        log.error("Cannot read API key from file {}, skipping", applicationKeyFile, e);
      }
    }
    applicationKey = tmpApplicationKey;

    String userProvidedServiceName =
        configProvider.getStringExcludingSource(
            SERVICE, null, CapturedEnvironmentConfigSource.class, SERVICE_NAME);

    if (userProvidedServiceName == null) {
      serviceNameSetByUser = false;
      serviceName = configProvider.getString(SERVICE, DEFAULT_SERVICE_NAME, SERVICE_NAME);
    } else {
      // might be an auto-detected name propagated from instrumented parent process
      serviceNameSetByUser = configProvider.getBoolean(SERVICE_NAME_SET_BY_USER, true);
      serviceName = userProvidedServiceName;
    }

    rootContextServiceName =
        configProvider.getString(
            SERVLET_ROOT_CONTEXT_SERVICE_NAME, DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME);

    experimentalFeaturesEnabled =
        configProvider.getString(TRACE_EXPERIMENTAL_FEATURES_ENABLED, "").equals("all")
            ? DEFAULT_TRACE_EXPERIMENTAL_FEATURES_ENABLED
            : configProvider.getSet(TRACE_EXPERIMENTAL_FEATURES_ENABLED, new HashSet<>());

    integrationSynapseLegacyOperationName =
        configProvider.getBoolean(INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME, false);
    writerType = configProvider.getString(WRITER_TYPE, DEFAULT_AGENT_WRITER_TYPE);
    injectBaggageAsTagsEnabled =
        configProvider.getBoolean(WRITER_BAGGAGE_INJECT, DEFAULT_WRITER_BAGGAGE_INJECT);
    String lambdaInitType = getEnv("AWS_LAMBDA_INITIALIZATION_TYPE");
    if (lambdaInitType != null && lambdaInitType.equals("snap-start")) {
      secureRandom = true;
    } else {
      secureRandom = configProvider.getBoolean(SECURE_RANDOM, DEFAULT_SECURE_RANDOM);
    }
    cassandraKeyspaceStatementExtractionEnabled =
        configProvider.getBoolean(
            CASSANDRA_KEYSPACE_STATEMENT_EXTRACTION_ENABLED,
            DEFAULT_CASSANDRA_KEYSPACE_STATEMENT_EXTRACTION_ENABLED);
    couchbaseInternalSpansEnabled =
        configProvider.getBoolean(
            COUCHBASE_INTERNAL_SPANS_ENABLED, DEFAULT_COUCHBASE_INTERNAL_SPANS_ENABLED);
    elasticsearchBodyEnabled =
        configProvider.getBoolean(ELASTICSEARCH_BODY_ENABLED, DEFAULT_ELASTICSEARCH_BODY_ENABLED);
    elasticsearchParamsEnabled =
        configProvider.getBoolean(
            ELASTICSEARCH_PARAMS_ENABLED, DEFAULT_ELASTICSEARCH_PARAMS_ENABLED);
    elasticsearchBodyAndParamsEnabled =
        configProvider.getBoolean(
            ELASTICSEARCH_BODY_AND_PARAMS_ENABLED, DEFAULT_ELASTICSEARCH_BODY_AND_PARAMS_ENABLED);
    String strategyName = configProvider.getString(ID_GENERATION_STRATEGY);
    trace128bitTraceIdGenerationEnabled =
        configProvider.getBoolean(
            TRACE_128_BIT_TRACEID_GENERATION_ENABLED,
            DEFAULT_TRACE_128_BIT_TRACEID_GENERATION_ENABLED);

    logs128bitTraceIdEnabled =
        configProvider.getBoolean(
            TRACE_128_BIT_TRACEID_LOGGING_ENABLED, DEFAULT_TRACE_128_BIT_TRACEID_LOGGING_ENABLED);

    if (secureRandom) {
      strategyName = "SECURE_RANDOM";
    }
    if (strategyName == null) {
      strategyName = "RANDOM";
    }
    IdGenerationStrategy strategy =
        IdGenerationStrategy.fromName(strategyName, trace128bitTraceIdGenerationEnabled);
    if (strategy == null) {
      log.warn(
          "*** you are trying to use an unknown id generation strategy {} - falling back to RANDOM",
          strategyName);
      strategyName = "RANDOM";
      strategy = IdGenerationStrategy.fromName(strategyName, trace128bitTraceIdGenerationEnabled);
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

    // avoid merging in supplementary host/port settings when dealing with unix: URLs
    if (unixSocketFromEnvironment == null) {
      if (agentHostFromEnvironment == null) {
        agentHostFromEnvironment = configProvider.getString(AGENT_HOST);
        rebuildAgentUrl = true;
      }
      if (agentPortFromEnvironment < 0) {
        agentPortFromEnvironment =
            configProvider.getInteger(TRACE_AGENT_PORT, -1, AGENT_PORT_LEGACY);
        rebuildAgentUrl = true;
      }
    }

    if (agentHostFromEnvironment == null) {
      agentHost = DEFAULT_AGENT_HOST;
    } else if (agentHostFromEnvironment.charAt(0) == '[') {
      agentHost = agentHostFromEnvironment.substring(1, agentHostFromEnvironment.length() - 1);
    } else {
      agentHost = agentHostFromEnvironment;
    }

    if (agentPortFromEnvironment < 0) {
      agentPort = DEFAULT_TRACE_AGENT_PORT;
    } else {
      agentPort = agentPortFromEnvironment;
    }

    if (rebuildAgentUrl) { // check if agenthost contains ':'
      if (agentHost.indexOf(':') != -1) { // Checking to see whether host address is IPv6 vs IPv4
        agentUrl = "http://[" + agentHost + "]:" + agentPort;
      } else {
        agentUrl = "http://" + agentHost + ":" + agentPort;
      }
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

    forceClearTextHttpForIntakeClient =
        configProvider.getBoolean(FORCE_CLEAR_TEXT_HTTP_FOR_INTAKE_CLIENT, false);

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
      if (experimentalFeaturesEnabled.contains("DD_TAGS")) {
        tags.putAll(configProvider.getMergedTagsMap(TRACE_TAGS, TAGS));
      } else {
        tags.putAll(configProvider.getMergedMap(TRACE_TAGS, TAGS));
      }
      if (serviceNameSetByUser) { // prioritize service name set by DD_SERVICE over DD_TAGS config
        tags.remove("service");
      }
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
    requestHeaderTagsCommaAllowed =
        configProvider.getBoolean(REQUEST_HEADER_TAGS_COMMA_ALLOWED, true);

    baggageMapping = configProvider.getMergedMapWithOptionalMappings(null, true, BAGGAGE_MAPPING);

    azureFunctions =
        getEnv("FUNCTIONS_WORKER_RUNTIME") != null && getEnv("FUNCTIONS_EXTENSION_VERSION") != null;

    awsServerless =
        getEnv("AWS_LAMBDA_FUNCTION_NAME") != null && !getEnv("AWS_LAMBDA_FUNCTION_NAME").isEmpty();

    spanAttributeSchemaVersion = schemaVersionFromConfig();

    peerHostNameEnabled = configProvider.getBoolean(TRACE_PEER_HOSTNAME_ENABLED, true);

    // following two only used in v0.
    // in v1+ defaults are always calculated regardless this feature flag
    peerServiceDefaultsEnabled =
        configProvider.getBoolean(TRACE_PEER_SERVICE_DEFAULTS_ENABLED, false);
    peerServiceComponentOverrides =
        configProvider.getMergedMap(TRACE_PEER_SERVICE_COMPONENT_OVERRIDES);
    // feature flag to remove fake services in v0
    removeIntegrationServiceNamesEnabled =
        configProvider.getBoolean(TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED, false);
    experimentalPropagateProcessTagsEnabled =
        configProvider.getBoolean(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, true);

    peerServiceMapping = configProvider.getMergedMap(TRACE_PEER_SERVICE_MAPPING);

    httpServerPathResourceNameMapping =
        configProvider.getOrderedMap(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING);

    httpClientPathResourceNameMapping =
        configProvider.getOrderedMap(TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING);

    httpResourceRemoveTrailingSlash =
        configProvider.getBoolean(
            TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH,
            DEFAULT_TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH);

    httpServerErrorStatuses =
        configProvider.getIntegerRange(
            TRACE_HTTP_SERVER_ERROR_STATUSES,
            DEFAULT_HTTP_SERVER_ERROR_STATUSES,
            HTTP_SERVER_ERROR_STATUSES);

    httpClientErrorStatuses =
        configProvider.getIntegerRange(
            TRACE_HTTP_CLIENT_ERROR_STATUSES,
            DEFAULT_HTTP_CLIENT_ERROR_STATUSES,
            HTTP_CLIENT_ERROR_STATUSES);

    httpServerTagQueryString =
        configProvider.getBoolean(
            HTTP_SERVER_TAG_QUERY_STRING, DEFAULT_HTTP_SERVER_TAG_QUERY_STRING);

    httpServerRawQueryString = configProvider.getBoolean(HTTP_SERVER_RAW_QUERY_STRING, true);

    httpServerRawResource = configProvider.getBoolean(HTTP_SERVER_RAW_RESOURCE, false);

    httpServerDecodedResourcePreserveSpaces =
        configProvider.getBoolean(HTTP_SERVER_DECODED_RESOURCE_PRESERVE_SPACES, true);

    httpServerRouteBasedNaming =
        configProvider.getBoolean(
            HTTP_SERVER_ROUTE_BASED_NAMING, DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING);

    httpClientTagQueryString =
        configProvider.getBoolean(
            TRACE_HTTP_CLIENT_TAG_QUERY_STRING,
            DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING,
            HTTP_CLIENT_TAG_QUERY_STRING);

    httpClientTagHeaders = configProvider.getBoolean(HTTP_CLIENT_TAG_HEADERS, true);

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

    dbClientSplitByHost =
        configProvider.getBoolean(
            DB_CLIENT_HOST_SPLIT_BY_HOST, DEFAULT_DB_CLIENT_HOST_SPLIT_BY_HOST);

    dbmPropagationMode =
        configProvider.getString(
            DB_DBM_PROPAGATION_MODE_MODE, DEFAULT_DB_DBM_PROPAGATION_MODE_MODE);

    dbmTracePreparedStatements =
        configProvider.getBoolean(
            DB_DBM_TRACE_PREPARED_STATEMENTS, DEFAULT_DB_DBM_TRACE_PREPARED_STATEMENTS);

    dbmInjectSqlBaseHash = configProvider.getBoolean(DB_DBM_INJECT_SQL_BASEHASH, false);

    splitByTags = tryMakeImmutableSet(configProvider.getList(SPLIT_BY_TAGS));

    jeeSplitByDeployment =
        configProvider.getBoolean(
            EXPERIMENTATAL_JEE_SPLIT_BY_DEPLOYMENT, DEFAULT_EXPERIMENTATAL_JEE_SPLIT_BY_DEPLOYMENT);

    springDataRepositoryInterfaceResourceName =
        configProvider.getBoolean(SPRING_DATA_REPOSITORY_INTERFACE_RESOURCE_NAME, true);

    scopeDepthLimit = configProvider.getInteger(SCOPE_DEPTH_LIMIT, DEFAULT_SCOPE_DEPTH_LIMIT);

    scopeStrictMode = configProvider.getBoolean(SCOPE_STRICT_MODE, false);

    scopeIterationKeepAlive =
        configProvider.getInteger(SCOPE_ITERATION_KEEP_ALIVE, DEFAULT_SCOPE_ITERATION_KEEP_ALIVE);

    boolean partialFlushEnabled = configProvider.getBoolean(PARTIAL_FLUSH_ENABLED, true);
    partialFlushMinSpans =
        !partialFlushEnabled
            ? 0
            : configProvider.getInteger(PARTIAL_FLUSH_MIN_SPANS, DEFAULT_PARTIAL_FLUSH_MIN_SPANS);

    traceKeepLatencyThreshold =
        configProvider.getInteger(
            TRACE_KEEP_LATENCY_THRESHOLD_MS, DEFAULT_TRACE_KEEP_LATENCY_THRESHOLD_MS);

    traceKeepLatencyThresholdEnabled = !partialFlushEnabled && (traceKeepLatencyThreshold > 0);

    traceStrictWritesEnabled = configProvider.getBoolean(TRACE_STRICT_WRITES_ENABLED, false);

    logExtractHeaderNames =
        configProvider.getBoolean(
            PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED,
            DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED);

    tracePropagationStyleB3PaddingEnabled =
        isEnabled(true, TRACE_PROPAGATION_STYLE, ".b3.padding.enabled");

    TracePropagationBehaviorExtract tmpTracePropagationBehaviorExtract;
    try {
      tmpTracePropagationBehaviorExtract =
          TracePropagationBehaviorExtract.valueOf(
              configProvider
                  .getString(
                      TRACE_PROPAGATION_BEHAVIOR_EXTRACT,
                      DEFAULT_TRACE_PROPAGATION_BEHAVIOR_EXTRACT.toString())
                  .toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      tmpTracePropagationBehaviorExtract = TracePropagationBehaviorExtract.CONTINUE;
      log.warn("Error while parsing TRACE_PROPAGATION_BEHAVIOR_EXTRACT, defaulting to `continue`");
    }
    tracePropagationBehaviorExtract = tmpTracePropagationBehaviorExtract;

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
              PROPAGATION_STYLE_EXTRACT, PropagationStyle::valueOfConfigName, true);
      Set<PropagationStyle> deprecatedInject =
          getSettingsSetFromEnvironment(
              PROPAGATION_STYLE_INJECT, PropagationStyle::valueOfConfigName, true);
      Set<TracePropagationStyle> common =
          getSettingsSetFromEnvironment(
              TRACE_PROPAGATION_STYLE, TracePropagationStyle::valueOfDisplayName, false);
      Set<TracePropagationStyle> extract =
          getSettingsSetFromEnvironment(
              TRACE_PROPAGATION_STYLE_EXTRACT, TracePropagationStyle::valueOfDisplayName, false);
      Set<TracePropagationStyle> inject =
          getSettingsSetFromEnvironment(
              TRACE_PROPAGATION_STYLE_INJECT, TracePropagationStyle::valueOfDisplayName, false);
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

      // Parse the baggage tag keys configuration
      traceBaggageTagKeys =
          configProvider.getList(TRACE_BAGGAGE_TAG_KEYS, DEFAULT_TRACE_BAGGAGE_TAG_KEYS);

      // Now we can check if we should pick the default injection/extraction

      tracePropagationStylesToExtract =
          extract.isEmpty() ? DEFAULT_TRACE_PROPAGATION_STYLE : extract;

      tracePropagationStylesToInject = inject.isEmpty() ? DEFAULT_TRACE_PROPAGATION_STYLE : inject;

      traceBaggageMaxItems =
          configProvider.getInteger(TRACE_BAGGAGE_MAX_ITEMS, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS);
      traceBaggageMaxBytes =
          configProvider.getInteger(TRACE_BAGGAGE_MAX_BYTES, DEFAULT_TRACE_BAGGAGE_MAX_BYTES);

      // These setting are here for backwards compatibility until they can be removed in a major
      // release of the tracer
      propagationStylesToExtract =
          deprecatedExtract.isEmpty() ? DEFAULT_PROPAGATION_STYLE : deprecatedExtract;
      propagationStylesToInject =
          deprecatedInject.isEmpty() ? DEFAULT_PROPAGATION_STYLE : deprecatedInject;
    }

    tracePropagationExtractFirst =
        configProvider.getBoolean(
            TRACE_PROPAGATION_EXTRACT_FIRST, DEFAULT_TRACE_PROPAGATION_EXTRACT_FIRST);
    traceInferredProxyEnabled =
        configProvider.getBoolean(TRACE_INFERRED_PROXY_SERVICES_ENABLED, false);

    clockSyncPeriod = configProvider.getInteger(CLOCK_SYNC_PERIOD, DEFAULT_CLOCK_SYNC_PERIOD);

    if (experimentalFeaturesEnabled.contains(
        propertyNameToEnvironmentVariableName(LOGS_INJECTION))) {
      logsInjectionEnabled =
          configProvider.getBoolean(LOGS_INJECTION_ENABLED, false, LOGS_INJECTION);
    } else {
      logsInjectionEnabled =
          configProvider.getBoolean(
              LOGS_INJECTION_ENABLED, DEFAULT_LOGS_INJECTION_ENABLED, LOGS_INJECTION);
    }

    dogStatsDNamedPipe = configProvider.getString(DOGSTATSD_NAMED_PIPE);

    dogStatsDStartDelay =
        configProvider.getInteger(
            DOGSTATSD_START_DELAY, DEFAULT_DOGSTATSD_START_DELAY, JMX_FETCH_START_DELAY);

    dogStatsDPort = configProvider.getInteger(DOGSTATSD_PORT, DEFAULT_DOGSTATSD_PORT);

    statsDClientQueueSize = configProvider.getInteger(STATSD_CLIENT_QUEUE_SIZE);
    statsDClientSocketBuffer = configProvider.getInteger(STATSD_CLIENT_SOCKET_BUFFER);
    statsDClientSocketTimeout = configProvider.getInteger(STATSD_CLIENT_SOCKET_TIMEOUT);

    runtimeMetricsEnabled = configProvider.getBoolean(RUNTIME_METRICS_ENABLED, true);

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

    tracerMetricsEnabled =
        configProvider.getBoolean(TRACE_STATS_COMPUTATION_ENABLED, true, TRACER_METRICS_ENABLED);
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

    traceGitMetadataEnabled = configProvider.getBoolean(TRACE_GIT_METADATA_ENABLED, true);

    traceSamplingServiceRules = configProvider.getMergedMap(TRACE_SAMPLING_SERVICE_RULES);
    traceSamplingOperationRules = configProvider.getMergedMap(TRACE_SAMPLING_OPERATION_RULES);
    traceSamplingRules = configProvider.getString(TRACE_SAMPLING_RULES);
    traceSampleRate = configProvider.getDouble(TRACE_SAMPLE_RATE);
    traceRateLimit = configProvider.getInteger(TRACE_RATE_LIMIT, DEFAULT_TRACE_RATE_LIMIT);
    spanSamplingRules = configProvider.getString(SPAN_SAMPLING_RULES);
    spanSamplingRulesFile = configProvider.getString(SPAN_SAMPLING_RULES_FILE);

    // For the native image 'instrumenterConfig.isProfilingEnabled()' value will be 'baked-in' based
    // on whether
    // the profiler was enabled at build time or not.
    // Otherwise just do the standard config lookup by key.
    // An extra step is needed to properly handle the 'auto' value for profiling enablement via SSI.
    String value =
        configProvider.getString(
            ProfilingConfig.PROFILING_ENABLED,
            String.valueOf(instrumenterConfig.isProfilingEnabled()));
    // Run a validator that will emit a warning if the value is not a valid ProfilingEnablement
    // We don't want it to run in each call to ProfilingEnablement.of(value) not to flood the logs
    ProfilingEnablement.validate(value);
    profilingEnabled = ProfilingEnablement.of(value);
    profilingAgentless =
        configProvider.getBoolean(PROFILING_AGENTLESS, PROFILING_AGENTLESS_DEFAULT);
    isDatadogProfilerEnabled =
        !isDatadogProfilerEnablementOverridden()
            && configProvider.getBoolean(
                PROFILING_DATADOG_PROFILER_ENABLED, isDatadogProfilerSafeInCurrentEnvironment())
            && !(Platform.isNativeImageBuilder() || Platform.isNativeImage());
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
    int profilingStartDelayValue =
        configProvider.getInteger(PROFILING_START_DELAY, PROFILING_START_DELAY_DEFAULT);
    boolean profilingStartForceFirstValue =
        configProvider.getBoolean(PROFILING_START_FORCE_FIRST, PROFILING_START_FORCE_FIRST_DEFAULT);
    if (profilingEnabled == ProfilingEnablement.AUTO
        || profilingEnabled == ProfilingEnablement.INJECTED) {
      if (profilingStartDelayValue != PROFILING_START_DELAY_DEFAULT) {
        log.info(
            "Profiling start delay is set to {}s, but profiling enablement is set to auto. Using the default delay of {}s.",
            profilingStartDelayValue,
            PROFILING_START_DELAY_DEFAULT);
      }
      if (profilingStartForceFirstValue != PROFILING_START_FORCE_FIRST_DEFAULT) {
        log.info(
            "Profiling is requested to start immediately, but profiling enablement is set to auto. Profiling will be started with delay of {}s.",
            PROFILING_START_DELAY_DEFAULT);
      }
      profilingStartDelayValue = PROFILING_START_DELAY_DEFAULT;
      profilingStartForceFirstValue = PROFILING_START_FORCE_FIRST_DEFAULT;
    }
    profilingStartDelay = profilingStartDelayValue;
    profilingStartForceFirst = profilingStartForceFirstValue;
    profilingUploadPeriod =
        configProvider.getInteger(PROFILING_UPLOAD_PERIOD, PROFILING_UPLOAD_PERIOD_DEFAULT);
    profilingTemplateOverrideFile = configProvider.getString(PROFILING_TEMPLATE_OVERRIDE_FILE);
    profilingUploadTimeout =
        configProvider.getInteger(PROFILING_UPLOAD_TIMEOUT, PROFILING_UPLOAD_TIMEOUT_DEFAULT);
    profilingUploadCompression =
        configProvider.getString(
            PROFILING_DEBUG_UPLOAD_COMPRESSION,
            PROFILING_DEBUG_UPLOAD_COMPRESSION_DEFAULT,
            PROFILING_UPLOAD_COMPRESSION);
    profilingProxyHost = configProvider.getString(PROFILING_PROXY_HOST);
    profilingProxyPort =
        configProvider.getInteger(PROFILING_PROXY_PORT, PROFILING_PROXY_PORT_DEFAULT);
    profilingProxyUsername = configProvider.getString(PROFILING_PROXY_USERNAME);
    profilingProxyPassword = configProvider.getString(PROFILING_PROXY_PASSWORD);

    profilingExceptionSampleLimit =
        configProvider.getInteger(
            PROFILING_EXCEPTION_SAMPLE_LIMIT, PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT);
    profilingBackPressureSampleLimit =
        configProvider.getInteger(
            PROFILING_EXCEPTION_SAMPLE_LIMIT, PROFILING_BACKPRESSURE_SAMPLE_LIMIT_DEFAULT);
    profilingBackPressureEnabled =
        configProvider.getBoolean(
            PROFILING_BACKPRESSURE_SAMPLING_ENABLED,
            PROFILING_BACKPRESSURE_SAMPLING_ENABLED_DEFAULT);
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

    profilingRecordExceptionMessage =
        configProvider.getBoolean(
            PROFILING_EXCEPTION_RECORD_MESSAGE, PROFILING_EXCEPTION_RECORD_MESSAGE_DEFAULT);

    profilingUploadSummaryOn413Enabled =
        configProvider.getBoolean(
            PROFILING_UPLOAD_SUMMARY_ON_413, PROFILING_UPLOAD_SUMMARY_ON_413_DEFAULT);

    crashTrackingAgentless =
        configProvider.getBoolean(CRASH_TRACKING_AGENTLESS, CRASH_TRACKING_AGENTLESS_DEFAULT);
    crashTrackingTags = configProvider.getMergedMap(CRASH_TRACKING_TAGS);

    float telemetryInterval =
        configProvider.getFloat(TELEMETRY_HEARTBEAT_INTERVAL, DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL);
    if (telemetryInterval < 0.1 || telemetryInterval > 3600) {
      log.warn(
          "Invalid Telemetry heartbeat interval: {}. The value must be in range 0.1-3600",
          telemetryInterval);
      telemetryInterval = DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL;
    }
    telemetryHeartbeatInterval = telemetryInterval;

    telemetryExtendedHeartbeatInterval =
        configProvider.getLong(
            TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL, DEFAULT_TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL);

    telemetryInterval =
        configProvider.getFloat(TELEMETRY_METRICS_INTERVAL, DEFAULT_TELEMETRY_METRICS_INTERVAL);
    if (telemetryInterval < 0.1 || telemetryInterval > 3600) {
      log.warn(
          "Invalid Telemetry metrics interval: {}. The value must be in range 0.1-3600",
          telemetryInterval);
      telemetryInterval = DEFAULT_TELEMETRY_METRICS_INTERVAL;
    }
    telemetryMetricsInterval = telemetryInterval;

    telemetryMetricsEnabled = configProvider.getBoolean(TELEMETRY_METRICS_ENABLED, true);

    isTelemetryLogCollectionEnabled =
        instrumenterConfig.isTelemetryEnabled()
            && configProvider.getBoolean(
                TELEMETRY_LOG_COLLECTION_ENABLED, DEFAULT_TELEMETRY_LOG_COLLECTION_ENABLED);

    isTelemetryDependencyServiceEnabled =
        configProvider.getBoolean(
            TELEMETRY_DEPENDENCY_COLLECTION_ENABLED,
            DEFAULT_TELEMETRY_DEPENDENCY_COLLECTION_ENABLED);
    telemetryDependencyResolutionQueueSize =
        configProvider.getInteger(
            TELEMETRY_DEPENDENCY_RESOLUTION_QUEUE_SIZE,
            DEFAULT_TELEMETRY_DEPENDENCY_RESOLUTION_QUEUE_SIZE);
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
    appSecUserIdCollectionMode =
        UserIdCollectionMode.fromString(
            configProvider.getStringNotEmpty(APPSEC_AUTO_USER_INSTRUMENTATION_MODE, null),
            configProvider.getStringNotEmpty(APPSEC_AUTOMATED_USER_EVENTS_TRACKING, null));
    appSecScaEnabled = configProvider.getBoolean(APPSEC_SCA_ENABLED);
    appSecRaspEnabled = configProvider.getBoolean(APPSEC_RASP_ENABLED, DEFAULT_APPSEC_RASP_ENABLED);
    appSecStackTraceEnabled =
        configProvider.getBoolean(
            APPSEC_STACK_TRACE_ENABLED,
            DEFAULT_APPSEC_STACK_TRACE_ENABLED,
            APPSEC_STACKTRACE_ENABLED_DEPRECATED);
    appSecMaxStackTraces =
        configProvider.getInteger(
            APPSEC_MAX_STACK_TRACES,
            DEFAULT_APPSEC_MAX_STACK_TRACES,
            APPSEC_MAX_STACKTRACES_DEPRECATED);
    appSecMaxStackTraceDepth =
        configProvider.getInteger(
            APPSEC_MAX_STACK_TRACE_DEPTH,
            DEFAULT_APPSEC_MAX_STACK_TRACE_DEPTH,
            APPSEC_MAX_STACKTRACE_DEPTH_DEPRECATED);
    appSecBodyParsingSizeLimit =
        configProvider.getInteger(
            APPSEC_BODY_PARSING_SIZE_LIMIT, DEFAULT_APPSEC_BODY_PARSING_SIZE_LIMIT);
    apiSecurityEnabled =
        configProvider.getBoolean(
            API_SECURITY_ENABLED, DEFAULT_API_SECURITY_ENABLED, API_SECURITY_ENABLED_EXPERIMENTAL);
    apiSecuritySampleDelay =
        configProvider.getFloat(API_SECURITY_SAMPLE_DELAY, DEFAULT_API_SECURITY_SAMPLE_DELAY);
    apiSecurityEndpointCollectionEnabled =
        configProvider.getBoolean(
            API_SECURITY_ENDPOINT_COLLECTION_ENABLED,
            DEFAULT_API_SECURITY_ENDPOINT_COLLECTION_ENABLED);
    apiSecurityEndpointCollectionMessageLimit =
        configProvider.getInteger(
            API_SECURITY_ENDPOINT_COLLECTION_MESSAGE_LIMIT,
            DEFAULT_API_SECURITY_ENDPOINT_COLLECTION_MESSAGE_LIMIT);
    apiSecurityMaxDownstreamRequestBodyAnalysis =
        configProvider.getInteger(
            API_SECURITY_MAX_DOWNSTREAM_REQUEST_BODY_ANALYSIS,
            DEFAULT_API_SECURITY_MAX_DOWNSTREAM_REQUEST_BODY_ANALYSIS);
    apiSecurityDownstreamRequestAnalysisSampleRate =
        configProvider.getDouble(
            API_SECURITY_DOWNSTREAM_REQUEST_ANALYSIS_SAMPLE_RATE,
            DEFAULT_API_SECURITY_DOWNSTREAM_REQUEST_ANALYSIS_SAMPLE_RATE);

    iastDebugEnabled = configProvider.getBoolean(IAST_DEBUG_ENABLED, DEFAULT_IAST_DEBUG_ENABLED);

    iastContextMode =
        configProvider.getEnum(IAST_CONTEXT_MODE, IastContext.Mode.class, IastContext.Mode.REQUEST);
    iastDetectionMode =
        configProvider.getEnum(IAST_DETECTION_MODE, IastDetectionMode.class, DEFAULT);
    iastMaxConcurrentRequests = iastDetectionMode.getIastMaxConcurrentRequests(configProvider);
    iastVulnerabilitiesPerRequest =
        iastDetectionMode.getIastVulnerabilitiesPerRequest(configProvider);
    iastRequestSampling = iastDetectionMode.getIastRequestSampling(configProvider);
    iastDeduplicationEnabled = iastDetectionMode.isIastDeduplicationEnabled(configProvider);
    iastWeakHashAlgorithms =
        tryMakeImmutableSet(
            configProvider.getSet(IAST_WEAK_HASH_ALGORITHMS, DEFAULT_IAST_WEAK_HASH_ALGORITHMS));
    iastWeakCipherAlgorithms =
        getPattern(
            DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS,
            configProvider.getString(IAST_WEAK_CIPHER_ALGORITHMS));
    iastTelemetryVerbosity =
        configProvider.getEnum(IAST_TELEMETRY_VERBOSITY, Verbosity.class, Verbosity.INFORMATION);
    iastRedactionEnabled =
        configProvider.getBoolean(IAST_REDACTION_ENABLED, DEFAULT_IAST_REDACTION_ENABLED);
    iastRedactionNamePattern =
        configProvider.getString(IAST_REDACTION_NAME_PATTERN, DEFAULT_IAST_REDACTION_NAME_PATTERN);
    iastRedactionValuePattern =
        configProvider.getString(
            IAST_REDACTION_VALUE_PATTERN, DEFAULT_IAST_REDACTION_VALUE_PATTERN);
    iastTruncationMaxValueLength =
        configProvider.getInteger(
            IAST_TRUNCATION_MAX_VALUE_LENGTH, DEFAULT_IAST_TRUNCATION_MAX_VALUE_LENGTH);
    iastMaxRangeCount = iastDetectionMode.getIastMaxRangeCount(configProvider);
    iastStacktraceLeakSuppress =
        configProvider.getBoolean(
            IAST_STACK_TRACE_LEAK_SUPPRESS,
            DEFAULT_IAST_STACKTRACE_LEAK_SUPPRESS,
            IAST_STACKTRACE_LEAK_SUPPRESS_DEPRECATED);
    iastHardcodedSecretEnabled =
        configProvider.getBoolean(
            IAST_HARDCODED_SECRET_ENABLED, DEFAULT_IAST_HARDCODED_SECRET_ENABLED);
    iastAnonymousClassesEnabled =
        configProvider.getBoolean(
            IAST_ANONYMOUS_CLASSES_ENABLED, DEFAULT_IAST_ANONYMOUS_CLASSES_ENABLED);
    iastSourceMappingEnabled = configProvider.getBoolean(IAST_SOURCE_MAPPING_ENABLED, false);
    iastSourceMappingMaxSize = configProvider.getInteger(IAST_SOURCE_MAPPING_MAX_SIZE, 1000);
    iastStackTraceEnabled =
        configProvider.getBoolean(
            IAST_STACK_TRACE_ENABLED,
            DEFAULT_IAST_STACK_TRACE_ENABLED,
            IAST_STACKTRACE_ENABLED_DEPRECATED);
    iastExperimentalPropagationEnabled =
        configProvider.getBoolean(IAST_EXPERIMENTAL_PROPAGATION_ENABLED, false);
    iastSecurityControlsConfiguration =
        configProvider.getString(IAST_SECURITY_CONTROLS_CONFIGURATION, null);
    iastDbRowsToTaint =
        configProvider.getInteger(IAST_DB_ROWS_TO_TAINT, DEFAULT_IAST_DB_ROWS_TO_TAINT);

    llmObsAgentlessEnabled =
        configProvider.getBoolean(LLMOBS_AGENTLESS_ENABLED, DEFAULT_LLM_OBS_AGENTLESS_ENABLED);
    final String tempLlmObsMlApp = configProvider.getString(LLMOBS_ML_APP);
    llmObsMlApp =
        tempLlmObsMlApp == null || tempLlmObsMlApp.isEmpty() ? serviceName : tempLlmObsMlApp;

    final String llmObsAgentlessUrlStr = getFinalLLMObsUrl();
    URI parsedLLMObsUri = null;
    if (llmObsAgentlessUrlStr != null && !llmObsAgentlessUrlStr.isEmpty()) {
      try {
        parsedLLMObsUri = new URL(llmObsAgentlessUrlStr).toURI();
      } catch (MalformedURLException | URISyntaxException ex) {
        log.error(
            "Cannot parse LLM Observability agentless URL '{}', skipping", llmObsAgentlessUrlStr);
      }
    }
    if (parsedLLMObsUri != null) {
      llmObsAgentlessUrl = llmObsAgentlessUrlStr;
    } else {
      llmObsAgentlessUrl = null;
    }

    ciVisibilityTraceSanitationEnabled =
        configProvider.getBoolean(CIVISIBILITY_TRACE_SANITATION_ENABLED, true);

    ciVisibilityAgentlessEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_AGENTLESS_ENABLED, DEFAULT_CIVISIBILITY_AGENTLESS_ENABLED);

    ciVisibilitySourceDataEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_SOURCE_DATA_ENABLED, DEFAULT_CIVISIBILITY_SOURCE_DATA_ENABLED);

    ciVisibilityBuildInstrumentationEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED,
            DEFAULT_CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED);

    final String ciVisibilityAgentlessUrlStr = configProvider.getString(CIVISIBILITY_AGENTLESS_URL);
    ciVisibilityAgentlessUrl =
        isValidUrl(ciVisibilityAgentlessUrlStr) ? ciVisibilityAgentlessUrlStr : null;

    final String ciVisibilityIntakeAgentlessUrlStr =
        configProvider.getString(CIVISIBILITY_INTAKE_AGENTLESS_URL);
    ciVisibilityIntakeAgentlessUrl =
        isValidUrl(ciVisibilityIntakeAgentlessUrlStr) ? ciVisibilityIntakeAgentlessUrlStr : null;

    ciVisibilityAgentJarUri = configProvider.getString(CIVISIBILITY_AGENT_JAR_URI);
    ciVisibilityAutoConfigurationEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_AUTO_CONFIGURATION_ENABLED,
            DEFAULT_CIVISIBILITY_AUTO_CONFIGURATION_ENABLED);
    ciVisibilityAdditionalChildProcessJvmArgs =
        configProvider.getString(CIVISIBILITY_ADDITIONAL_CHILD_PROCESS_JVM_ARGS);
    ciVisibilityCompilerPluginAutoConfigurationEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED,
            DEFAULT_CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED);
    ciVisibilityCodeCoverageEnabled =
        configProvider.getBoolean(CIVISIBILITY_CODE_COVERAGE_ENABLED, true);
    ciVisibilityCoverageLinesEnabled =
        configProvider.getBoolean(CIVISIBILITY_CODE_COVERAGE_LINES_ENABLED);
    ciVisibilityCodeCoverageReportDumpDir =
        configProvider.getString(CIVISIBILITY_CODE_COVERAGE_REPORT_DUMP_DIR);
    ciVisibilityCompilerPluginVersion =
        configProvider.getString(
            CIVISIBILITY_COMPILER_PLUGIN_VERSION, DEFAULT_CIVISIBILITY_COMPILER_PLUGIN_VERSION);
    ciVisibilityJacocoPluginVersion =
        configProvider.getString(
            CIVISIBILITY_JACOCO_PLUGIN_VERSION, DEFAULT_CIVISIBILITY_JACOCO_PLUGIN_VERSION);
    ciVisibilityJacocoPluginVersionProvided =
        configProvider.getString(CIVISIBILITY_JACOCO_PLUGIN_VERSION) != null;
    ciVisibilityCodeCoverageIncludes =
        Arrays.asList(
            COLON.split(configProvider.getString(CIVISIBILITY_CODE_COVERAGE_INCLUDES, ":")));
    ciVisibilityCodeCoverageExcludes =
        Arrays.asList(
            COLON.split(
                configProvider.getString(
                    CIVISIBILITY_CODE_COVERAGE_EXCLUDES,
                    DEFAULT_CIVISIBILITY_JACOCO_PLUGIN_EXCLUDES)));
    ciVisibilityCodeCoverageIncludedPackages =
        convertJacocoExclusionFormatToPackagePrefixes(ciVisibilityCodeCoverageIncludes);
    ciVisibilityCodeCoverageExcludedPackages =
        convertJacocoExclusionFormatToPackagePrefixes(ciVisibilityCodeCoverageExcludes);
    ciVisibilityJacocoGradleSourceSets =
        configProvider.getList(CIVISIBILITY_GRADLE_SOURCE_SETS, Arrays.asList("main", "test"));
    ciVisibilityCodeCoverageReportUploadEnabled =
        configProvider.getBoolean(CIVISIBILITY_CODE_COVERAGE_REPORT_UPLOAD_ENABLED, true);
    ciVisibilityDebugPort = configProvider.getInteger(CIVISIBILITY_DEBUG_PORT);
    ciVisibilityGitClientEnabled = configProvider.getBoolean(CIVISIBILITY_GIT_CLIENT_ENABLED, true);
    ciVisibilityGitUploadEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_GIT_UPLOAD_ENABLED, DEFAULT_CIVISIBILITY_GIT_UPLOAD_ENABLED);
    ciVisibilityGitUnshallowEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_GIT_UNSHALLOW_ENABLED, DEFAULT_CIVISIBILITY_GIT_UNSHALLOW_ENABLED);
    ciVisibilityGitUnshallowDefer =
        configProvider.getBoolean(CIVISIBILITY_GIT_UNSHALLOW_DEFER, true);
    ciVisibilityGitCommandTimeoutMillis =
        configProvider.getLong(
            CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS,
            DEFAULT_CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS);
    ciVisibilityBackendApiTimeoutMillis =
        configProvider.getLong(
            CIVISIBILITY_BACKEND_API_TIMEOUT_MILLIS,
            DEFAULT_CIVISIBILITY_BACKEND_API_TIMEOUT_MILLIS);
    ciVisibilityGitUploadTimeoutMillis =
        configProvider.getLong(
            CIVISIBILITY_GIT_UPLOAD_TIMEOUT_MILLIS, DEFAULT_CIVISIBILITY_GIT_UPLOAD_TIMEOUT_MILLIS);
    ciVisibilityGitRemoteName =
        configProvider.getString(
            CIVISIBILITY_GIT_REMOTE_NAME, DEFAULT_CIVISIBILITY_GIT_REMOTE_NAME);
    ciVisibilitySignalServerHost =
        configProvider.getString(
            CIVISIBILITY_SIGNAL_SERVER_HOST, DEFAULT_CIVISIBILITY_SIGNAL_SERVER_HOST);
    ciVisibilitySignalServerPort =
        configProvider.getInteger(
            CIVISIBILITY_SIGNAL_SERVER_PORT, DEFAULT_CIVISIBILITY_SIGNAL_SERVER_PORT);
    ciVisibilitySignalClientTimeoutMillis =
        configProvider.getInteger(CIVISIBILITY_SIGNAL_CLIENT_TIMEOUT_MILLIS, 10_000);
    ciVisibilityItrEnabled = configProvider.getBoolean(CIVISIBILITY_ITR_ENABLED, true);
    ciVisibilityTestSkippingEnabled =
        configProvider.getBoolean(CIVISIBILITY_TEST_SKIPPING_ENABLED, true);
    ciVisibilityCiProviderIntegrationEnabled =
        configProvider.getBoolean(CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED, true);
    ciVisibilityRepoIndexDuplicateKeyCheckEnabled =
        configProvider.getBoolean(CIVISIBILITY_REPO_INDEX_DUPLICATE_KEY_CHECK_ENABLED, true);
    ciVisibilityRepoIndexFollowSymlinks =
        configProvider.getBoolean(CIVISIBILITY_REPO_INDEX_FOLLOW_SYMLINKS, false);
    ciVisibilityExecutionSettingsCacheSize =
        configProvider.getInteger(CIVISIBILITY_EXECUTION_SETTINGS_CACHE_SIZE, 16);
    ciVisibilityJvmInfoCacheSize = configProvider.getInteger(CIVISIBILITY_JVM_INFO_CACHE_SIZE, 8);
    ciVisibilityCoverageRootPackagesLimit =
        configProvider.getInteger(CIVISIBILITY_CODE_COVERAGE_ROOT_PACKAGES_LIMIT, 50);
    ciVisibilityInjectedTracerVersion =
        configProvider.getString(CIVISIBILITY_INJECTED_TRACER_VERSION);
    ciVisibilityResourceFolderNames =
        configProvider.getList(
            CIVISIBILITY_RESOURCE_FOLDER_NAMES, DEFAULT_CIVISIBILITY_RESOURCE_FOLDER_NAMES);
    ciVisibilityFlakyRetryEnabled =
        configProvider.getBoolean(CIVISIBILITY_FLAKY_RETRY_ENABLED, true);
    ciVisibilityImpactedTestsDetectionEnabled =
        configProvider.getBoolean(CIVISIBILITY_IMPACTED_TESTS_DETECTION_ENABLED, true);
    ciVisibilityKnownTestsRequestEnabled =
        configProvider.getBoolean(CIVISIBILITY_KNOWN_TESTS_REQUEST_ENABLED, true);
    ciVisibilityFlakyRetryOnlyKnownFlakes =
        configProvider.getBoolean(CIVISIBILITY_FLAKY_RETRY_ONLY_KNOWN_FLAKES, false);
    ciVisibilityEarlyFlakeDetectionEnabled =
        configProvider.getBoolean(CIVISIBILITY_EARLY_FLAKE_DETECTION_ENABLED, true);
    ciVisibilityEarlyFlakeDetectionLowerLimit =
        configProvider.getInteger(CIVISIBILITY_EARLY_FLAKE_DETECTION_LOWER_LIMIT, 30);
    ciVisibilityFlakyRetryCount = configProvider.getInteger(CIVISIBILITY_FLAKY_RETRY_COUNT, 5);
    ciVisibilityTotalFlakyRetryCount =
        configProvider.getInteger(CIVISIBILITY_TOTAL_FLAKY_RETRY_COUNT, 1000);
    ciVisibilitySessionName = configProvider.getString(TEST_SESSION_NAME);
    ciVisibilityModuleName = configProvider.getString(CIVISIBILITY_MODULE_NAME);
    ciVisibilityTestCommand = configProvider.getString(CIVISIBILITY_TEST_COMMAND);
    ciVisibilityTelemetryEnabled = configProvider.getBoolean(CIVISIBILITY_TELEMETRY_ENABLED, true);
    ciVisibilityRumFlushWaitMillis =
        configProvider.getLong(CIVISIBILITY_RUM_FLUSH_WAIT_MILLIS, 500);
    ciVisibilityAutoInjected =
        Strings.isNotBlank(configProvider.getString(CIVISIBILITY_AUTO_INSTRUMENTATION_PROVIDER));
    ciVisibilityTestOrder = configProvider.getString(CIVISIBILITY_TEST_ORDER);
    ciVisibilityTestManagementEnabled = configProvider.getBoolean(TEST_MANAGEMENT_ENABLED, true);
    ciVisibilityTestManagementAttemptToFixRetries =
        configProvider.getInteger(TEST_MANAGEMENT_ATTEMPT_TO_FIX_RETRIES);
    ciVisibilityScalatestForkMonitorEnabled =
        configProvider.getBoolean(CIVISIBILITY_SCALATEST_FORK_MONITOR_ENABLED, false);
    gitPullRequestBaseBranch = configProvider.getString(GIT_PULL_REQUEST_BASE_BRANCH);
    gitPullRequestBaseBranchSha = configProvider.getString(GIT_PULL_REQUEST_BASE_BRANCH_SHA);
    gitCommitHeadSha = configProvider.getString(GIT_COMMIT_HEAD_SHA);
    ciVisibilityFailedTestReplayEnabled =
        configProvider.getBoolean(TEST_FAILED_TEST_REPLAY_ENABLED, true);

    remoteConfigEnabled =
        configProvider.getBoolean(
            REMOTE_CONFIGURATION_ENABLED, DEFAULT_REMOTE_CONFIG_ENABLED, REMOTE_CONFIG_ENABLED);
    remoteConfigIntegrityCheckEnabled =
        configProvider.getBoolean(
            REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED, DEFAULT_REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED);
    remoteConfigUrl = configProvider.getString(REMOTE_CONFIG_URL);
    remoteConfigPollIntervalSeconds =
        configProvider.getFloat(
            REMOTE_CONFIG_POLL_INTERVAL_SECONDS, DEFAULT_REMOTE_CONFIG_POLL_INTERVAL_SECONDS);
    remoteConfigMaxPayloadSize =
        configProvider.getInteger(
                REMOTE_CONFIG_MAX_PAYLOAD_SIZE, DEFAULT_REMOTE_CONFIG_MAX_PAYLOAD_SIZE)
            * 1024L;
    remoteConfigTargetsKeyId =
        configProvider.getString(
            REMOTE_CONFIG_TARGETS_KEY_ID, DEFAULT_REMOTE_CONFIG_TARGETS_KEY_ID);
    remoteConfigTargetsKey =
        configProvider.getString(REMOTE_CONFIG_TARGETS_KEY, DEFAULT_REMOTE_CONFIG_TARGETS_KEY);

    remoteConfigMaxExtraServices =
        configProvider.getInteger(
            REMOTE_CONFIG_MAX_EXTRA_SERVICES, DEFAULT_REMOTE_CONFIG_MAX_EXTRA_SERVICES);

    dynamicInstrumentationEnabled =
        configProvider.getBoolean(
            DYNAMIC_INSTRUMENTATION_ENABLED, DEFAULT_DYNAMIC_INSTRUMENTATION_ENABLED);
    dynamicInstrumentationSnapshotUrl =
        configProvider.getString(DYNAMIC_INSTRUMENTATION_SNAPSHOT_URL);
    distributedDebuggerEnabled =
        configProvider.getBoolean(
            DISTRIBUTED_DEBUGGER_ENABLED, DEFAULT_DISTRIBUTED_DEBUGGER_ENABLED);
    dynamicInstrumentationUploadTimeout =
        configProvider.getInteger(
            DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT, DEFAULT_DYNAMIC_INSTRUMENTATION_UPLOAD_TIMEOUT);
    if (configProvider.isSet(DYNAMIC_INSTRUMENTATION_UPLOAD_INTERVAL_SECONDS)) {
      dynamicInstrumentationUploadFlushInterval =
          (int)
              (configProvider.getFloat(
                      DYNAMIC_INSTRUMENTATION_UPLOAD_INTERVAL_SECONDS,
                      DEFAULT_DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL)
                  * 1000);
    } else {
      dynamicInstrumentationUploadFlushInterval =
          configProvider.getInteger(
              DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL,
              DEFAULT_DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL);
    }
    dynamicInstrumentationClassFileDumpEnabled =
        configProvider.getBoolean(
            DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED,
            DEFAULT_DYNAMIC_INSTRUMENTATION_CLASSFILE_DUMP_ENABLED);
    dynamicInstrumentationPollInterval =
        configProvider.getInteger(
            DYNAMIC_INSTRUMENTATION_POLL_INTERVAL, DEFAULT_DYNAMIC_INSTRUMENTATION_POLL_INTERVAL);
    dynamicInstrumentationDiagnosticsInterval =
        configProvider.getInteger(
            DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL,
            DEFAULT_DYNAMIC_INSTRUMENTATION_DIAGNOSTICS_INTERVAL);
    dynamicInstrumentationMetricEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(
                DYNAMIC_INSTRUMENTATION_METRICS_ENABLED,
                DEFAULT_DYNAMIC_INSTRUMENTATION_METRICS_ENABLED);
    dynamicInstrumentationProbeFile = configProvider.getString(DYNAMIC_INSTRUMENTATION_PROBE_FILE);
    dynamicInstrumentationUploadBatchSize =
        configProvider.getInteger(
            DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE,
            DEFAULT_DYNAMIC_INSTRUMENTATION_UPLOAD_BATCH_SIZE);
    dynamicInstrumentationMaxPayloadSize =
        configProvider.getInteger(
                DYNAMIC_INSTRUMENTATION_MAX_PAYLOAD_SIZE,
                DEFAULT_DYNAMIC_INSTRUMENTATION_MAX_PAYLOAD_SIZE)
            * 1024L;
    dynamicInstrumentationVerifyByteCode =
        configProvider.getBoolean(
            DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE,
            DEFAULT_DYNAMIC_INSTRUMENTATION_VERIFY_BYTECODE);
    dynamicInstrumentationInstrumentTheWorld =
        configProvider.getString(DYNAMIC_INSTRUMENTATION_INSTRUMENT_THE_WORLD);
    dynamicInstrumentationExcludeFiles =
        configProvider.getString(DYNAMIC_INSTRUMENTATION_EXCLUDE_FILES);
    dynamicInstrumentationIncludeFiles =
        configProvider.getString(DYNAMIC_INSTRUMENTATION_INCLUDE_FILES);
    dynamicInstrumentationCaptureTimeout =
        configProvider.getInteger(
            DYNAMIC_INSTRUMENTATION_CAPTURE_TIMEOUT,
            DEFAULT_DYNAMIC_INSTRUMENTATION_CAPTURE_TIMEOUT);
    dynamicInstrumentationRedactedIdentifiers =
        configProvider.getString(DYNAMIC_INSTRUMENTATION_REDACTED_IDENTIFIERS, null);
    dynamicInstrumentationRedactionExcludedIdentifiers =
        tryMakeImmutableSet(
            configProvider.getList(DYNAMIC_INSTRUMENTATION_REDACTION_EXCLUDED_IDENTIFIERS));
    dynamicInstrumentationRedactedTypes =
        configProvider.getString(DYNAMIC_INSTRUMENTATION_REDACTED_TYPES, null);
    dynamicInstrumentationLocalVarHoistingLevel =
        configProvider.getInteger(
            DYNAMIC_INSTRUMENTATION_LOCALVAR_HOISTING_LEVEL,
            DEFAULT_DYNAMIC_INSTRUMENTATION_LOCALVAR_HOISTING_LEVEL);
    symbolDatabaseEnabled =
        configProvider.getBoolean(SYMBOL_DATABASE_ENABLED, DEFAULT_SYMBOL_DATABASE_ENABLED);
    symbolDatabaseForceUpload =
        configProvider.getBoolean(
            SYMBOL_DATABASE_FORCE_UPLOAD, DEFAULT_SYMBOL_DATABASE_FORCE_UPLOAD);
    symbolDatabaseFlushThreshold =
        configProvider.getInteger(
            SYMBOL_DATABASE_FLUSH_THRESHOLD, DEFAULT_SYMBOL_DATABASE_FLUSH_THRESHOLD);
    symbolDatabaseCompressed =
        configProvider.getBoolean(SYMBOL_DATABASE_COMPRESSED, DEFAULT_SYMBOL_DATABASE_COMPRESSED);
    debuggerExceptionEnabled =
        configProvider.getBoolean(
            DEBUGGER_EXCEPTION_ENABLED,
            DEFAULT_DEBUGGER_EXCEPTION_ENABLED,
            EXCEPTION_REPLAY_ENABLED);
    debuggerCodeOriginEnabled =
        configProvider.getBoolean(
            CODE_ORIGIN_FOR_SPANS_ENABLED, DEFAULT_CODE_ORIGIN_FOR_SPANS_ENABLED);
    debuggerCodeOriginMaxUserFrames =
        configProvider.getInteger(CODE_ORIGIN_MAX_USER_FRAMES, DEFAULT_CODE_ORIGIN_MAX_USER_FRAMES);
    debuggerMaxExceptionPerSecond =
        configProvider.getInteger(
            DEBUGGER_MAX_EXCEPTION_PER_SECOND, DEFAULT_DEBUGGER_MAX_EXCEPTION_PER_SECOND);
    debuggerExceptionOnlyLocalRoot =
        configProvider.getBoolean(
            DEBUGGER_EXCEPTION_ONLY_LOCAL_ROOT, DEFAULT_DEBUGGER_EXCEPTION_ONLY_LOCAL_ROOT);
    debuggerExceptionCaptureIntermediateSpansEnabled =
        configProvider.getBoolean(
            DEBUGGER_EXCEPTION_CAPTURE_INTERMEDIATE_SPANS_ENABLED,
            DEFAULT_DEBUGGER_EXCEPTION_CAPTURE_INTERMEDIATE_SPANS_ENABLED);
    debuggerExceptionMaxCapturedFrames =
        configProvider.getInteger(
            DEBUGGER_EXCEPTION_MAX_CAPTURED_FRAMES,
            DEFAULT_DEBUGGER_EXCEPTION_MAX_CAPTURED_FRAMES,
            DEBUGGER_EXCEPTION_CAPTURE_MAX_FRAMES);
    debuggerExceptionCaptureInterval =
        configProvider.getInteger(
            DEBUGGER_EXCEPTION_CAPTURE_INTERVAL_SECONDS,
            DEFAULT_DEBUGGER_EXCEPTION_CAPTURE_INTERVAL_SECONDS);
    debuggerSourceFileTrackingEnabled =
        configProvider.getBoolean(
            DEBUGGER_SOURCE_FILE_TRACKING_ENABLED, DEFAULT_DEBUGGER_SOURCE_FILE_TRACKING_ENABLED);

    debuggerThirdPartyIncludes = tryMakeImmutableSet(configProvider.getList(THIRD_PARTY_INCLUDES));
    debuggerThirdPartyExcludes = tryMakeImmutableSet(configProvider.getList(THIRD_PARTY_EXCLUDES));
    debuggerShadingIdentifiers =
        tryMakeImmutableSet(configProvider.getList(THIRD_PARTY_SHADING_IDENTIFIERS));

    awsPropagationEnabled = isPropagationEnabled(true, "aws", "aws-sdk");
    sqsPropagationEnabled = isPropagationEnabled(true, "sqs");
    sqsBodyPropagationEnabled = configProvider.getBoolean(SQS_BODY_PROPAGATION_ENABLED, false);

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
    jmsUnacknowledgedMaxAge = configProvider.getInteger(JMS_UNACKNOWLEDGED_MAX_AGE, 3600);

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
    final List<String> tmpGrpcIgnoredOutboundMethods = new ArrayList<>();
    tmpGrpcIgnoredOutboundMethods.addAll(configProvider.getList(GRPC_IGNORED_OUTBOUND_METHODS));
    // When tracing shadowing will be possible we can instrument the stubs to silent tracing
    // starting from interception points
    if (InstrumenterConfig.get()
        .isIntegrationEnabled(Collections.singleton("google-pubsub"), true)) {
      tmpGrpcIgnoredOutboundMethods.addAll(
          configProvider.getList(
              GOOGLE_PUBSUB_IGNORED_GRPC_METHODS,
              Arrays.asList(
                  "google.pubsub.v1.Subscriber/ModifyAckDeadline",
                  "google.pubsub.v1.Subscriber/Acknowledge",
                  "google.pubsub.v1.Subscriber/Pull",
                  "google.pubsub.v1.Subscriber/StreamingPull",
                  "google.pubsub.v1.Publisher/Publish")));
    }
    grpcIgnoredOutboundMethods = tryMakeImmutableSet(tmpGrpcIgnoredOutboundMethods);
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

    resilience4jMeasuredEnabled = configProvider.getBoolean(RESILIENCE4J_MEASURED_ENABLED, false);
    resilience4jTagMetricsEnabled =
        configProvider.getBoolean(RESILIENCE4J_TAG_METRICS_ENABLED, false);

    igniteCacheIncludeKeys = configProvider.getBoolean(IGNITE_CACHE_INCLUDE_KEYS, false);

    obfuscationQueryRegexp =
        configProvider.getString(
            OBFUSCATION_QUERY_STRING_REGEXP, null, "obfuscation.query.string.regexp");

    playReportHttpStatus = configProvider.getBoolean(PLAY_REPORT_HTTP_STATUS, false);

    servletPrincipalEnabled = configProvider.getBoolean(SERVLET_PRINCIPAL_ENABLED, false);

    xDatadogTagsMaxLength =
        configProvider.getInteger(
            TRACE_X_DATADOG_TAGS_MAX_LENGTH, DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH);

    servletAsyncTimeoutError = configProvider.getBoolean(SERVLET_ASYNC_TIMEOUT_ERROR, true);

    logLevel = configProvider.getString(LOG_LEVEL);
    debugEnabled = configProvider.getBoolean(TRACE_DEBUG, false);
    triageEnabled = configProvider.getBoolean(TRACE_TRIAGE, instrumenterConfig.isTriageEnabled());
    triageReportTrigger = configProvider.getString(TRIAGE_REPORT_TRIGGER);
    if (null != triageReportTrigger) {
      triageReportDir = configProvider.getString(TRIAGE_REPORT_DIR, getProp("java.io.tmpdir"));
    } else {
      triageReportDir = null;
    }

    startupLogsEnabled =
        configProvider.getBoolean(STARTUP_LOGS_ENABLED, DEFAULT_STARTUP_LOGS_ENABLED);

    cwsEnabled = configProvider.getBoolean(CWS_ENABLED, DEFAULT_CWS_ENABLED);
    cwsTlsRefresh = configProvider.getInteger(CWS_TLS_REFRESH, DEFAULT_CWS_TLS_REFRESH);

    dataJobsEnabled = configProvider.getBoolean(DATA_JOBS_ENABLED, DEFAULT_DATA_JOBS_ENABLED);
    dataJobsOpenLineageEnabled =
        configProvider.getBoolean(
            DATA_JOBS_OPENLINEAGE_ENABLED, DEFAULT_DATA_JOBS_OPENLINEAGE_ENABLED);
    dataJobsOpenLineageTimeoutEnabled =
        configProvider.getBoolean(
            DATA_JOBS_OPENLINEAGE_TIMEOUT_ENABLED, DEFAULT_DATA_JOBS_OPENLINEAGE_TIMEOUT_ENABLED);

    dataStreamsEnabled =
        configProvider.getBoolean(DATA_STREAMS_ENABLED, DEFAULT_DATA_STREAMS_ENABLED);
    dataStreamsBucketDurationSeconds =
        configProvider.getFloat(
            DATA_STREAMS_BUCKET_DURATION_SECONDS, DEFAULT_DATA_STREAMS_BUCKET_DURATION);

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

    boolean longRunningEnabled =
        configProvider.getBoolean(TRACE_LONG_RUNNING_ENABLED, DEFAULT_TRACE_LONG_RUNNING_ENABLED);
    long longRunningTraceInitialFlushInterval =
        configProvider.getLong(
            TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL,
            DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL);
    long longRunningTraceFlushInterval =
        configProvider.getLong(
            TRACE_LONG_RUNNING_FLUSH_INTERVAL, DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL);
    serviceDiscoveryEnabled =
        configProvider.getBoolean(
            TRACE_SERVICE_DISCOVERY_ENABLED, DEFAULT_SERVICE_DISCOVERY_ENABLED);

    if (longRunningEnabled
        && (longRunningTraceInitialFlushInterval < 10
            || longRunningTraceInitialFlushInterval > 450)) {
      log.warn(
          "Provided long running trace initial flush interval of {} seconds. It should be between 10 seconds and 7.5 minutes."
              + "Setting the flush interval to the default value of {} seconds .",
          longRunningTraceInitialFlushInterval,
          DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL);
      longRunningTraceInitialFlushInterval = DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL;
    }
    if (longRunningEnabled
        && (longRunningTraceFlushInterval < 20 || longRunningTraceFlushInterval > 450)) {
      log.warn(
          "Provided long running trace flush interval of {} seconds. It should be between 20 seconds and 7.5 minutes."
              + "Setting the flush interval to the default value of {} seconds .",
          longRunningTraceFlushInterval,
          DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL);
      longRunningTraceFlushInterval = DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL;
    }
    this.longRunningTraceEnabled = longRunningEnabled;
    this.longRunningTraceInitialFlushInterval = longRunningTraceInitialFlushInterval;
    this.longRunningTraceFlushInterval = longRunningTraceFlushInterval;

    this.sparkTaskHistogramEnabled =
        configProvider.getBoolean(
            SPARK_TASK_HISTOGRAM_ENABLED, DEFAULT_SPARK_TASK_HISTOGRAM_ENABLED);

    this.sparkAppNameAsService =
        configProvider.getBoolean(SPARK_APP_NAME_AS_SERVICE, DEFAULT_SPARK_APP_NAME_AS_SERVICE);

    this.jaxRsExceptionAsErrorsEnabled =
        configProvider.getBoolean(
            JAX_RS_EXCEPTION_AS_ERROR_ENABLED, DEFAULT_JAX_RS_EXCEPTION_AS_ERROR_ENABLED);

    axisPromoteResourceName = configProvider.getBoolean(AXIS_PROMOTE_RESOURCE_NAME, false);

    websocketMessagesInheritSampling =
        configProvider.getBoolean(
            TRACE_WEBSOCKET_MESSAGES_INHERIT_SAMPLING, DEFAULT_WEBSOCKET_MESSAGES_INHERIT_SAMPLING);
    websocketMessagesSeparateTraces =
        configProvider.getBoolean(
            TRACE_WEBSOCKET_MESSAGES_SEPARATE_TRACES, DEFAULT_WEBSOCKET_MESSAGES_SEPARATE_TRACES);
    websocketTagSessionId =
        configProvider.getBoolean(TRACE_WEBSOCKET_TAG_SESSION_ID, DEFAULT_WEBSOCKET_TAG_SESSION_ID);

    this.traceFlushIntervalSeconds =
        configProvider.getFloat(
            TracerConfig.TRACE_FLUSH_INTERVAL, ConfigDefaults.DEFAULT_TRACE_FLUSH_INTERVAL);

    this.tracePostProcessingTimeout =
        configProvider.getLong(
            TRACE_POST_PROCESSING_TIMEOUT, DEFAULT_TRACE_POST_PROCESSING_TIMEOUT);

    if (isLlmObsEnabled()) {
      log.debug(
          "LLM Observability enabled for ML app {}, agentless mode {}",
          llmObsMlApp,
          llmObsAgentlessEnabled);
    }

    // if API key is not provided, check if any products are using agentless mode and require it
    if (apiKey == null || apiKey.isEmpty()) {
      // CI Visibility
      if (isCiVisibilityEnabled() && ciVisibilityAgentlessEnabled) {
        throw new FatalAgentMisconfigurationError(
            "Attempt to start in CI Visibility in Agentless mode without API key. "
                + "Please ensure that either an API key is configured, or the tracer is set up to work with the Agent");
      }

      // Profiling
      if (profilingAgentless) {
        log.warn(
            "Agentless profiling activated but no api key provided. Profile uploading will likely fail");
      }

      // LLM Observability
      if (isLlmObsEnabled() && llmObsAgentlessEnabled) {
        throw new FatalAgentMisconfigurationError(
            "Attempt to start LLM Observability in Agentless mode without API key. "
                + "Please ensure that either an API key is configured, or the tracer is set up to work with the Agent");
      }
    }

    this.telemetryDebugRequestsEnabled =
        configProvider.getBoolean(
            TELEMETRY_DEBUG_REQUESTS_ENABLED, DEFAULT_TELEMETRY_DEBUG_REQUESTS_ENABLED);

    this.agentlessLogSubmissionEnabled =
        configProvider.getBoolean(AGENTLESS_LOG_SUBMISSION_ENABLED, false);
    this.agentlessLogSubmissionQueueSize =
        configProvider.getInteger(AGENTLESS_LOG_SUBMISSION_QUEUE_SIZE, 1024);
    this.agentlessLogSubmissionLevel =
        configProvider.getString(AGENTLESS_LOG_SUBMISSION_LEVEL, "INFO");
    this.agentlessLogSubmissionUrl = configProvider.getString(AGENTLESS_LOG_SUBMISSION_URL);
    this.agentlessLogSubmissionProduct = isCiVisibilityEnabled() ? "citest" : "apm";

    this.cloudPayloadTaggingServices =
        configProvider.getSet(
            TRACE_CLOUD_PAYLOAD_TAGGING_SERVICES, DEFAULT_TRACE_CLOUD_PAYLOAD_TAGGING_SERVICES);
    this.cloudRequestPayloadTagging =
        configProvider.getList(TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, null);
    this.cloudResponsePayloadTagging =
        configProvider.getList(TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, null);
    this.cloudPayloadTaggingMaxDepth =
        configProvider.getInteger(TRACE_CLOUD_PAYLOAD_TAGGING_MAX_DEPTH, 10);
    this.cloudPayloadTaggingMaxTags =
        configProvider.getInteger(TRACE_CLOUD_PAYLOAD_TAGGING_MAX_TAGS, 758);

    this.dependecyResolutionPeriodMillis =
        configProvider.getLong(
            GeneralConfig.TELEMETRY_DEPENDENCY_RESOLUTION_PERIOD_MILLIS,
            1000); // 1 second by default

    timelineEventsEnabled =
        configProvider.getBoolean(
            PROFILING_TIMELINE_EVENTS_ENABLED, PROFILING_TIMELINE_EVENTS_ENABLED_DEFAULT);

    if (appSecScaEnabled != null
        && appSecScaEnabled
        && (!isTelemetryEnabled() || !isTelemetryDependencyServiceEnabled())) {
      log.warn(
          SEND_TELEMETRY,
          "AppSec SCA is enabled but telemetry is disabled. AppSec SCA will not work.");
    }

    // Used to report telemetry on SSI injection
    this.ssiInjectionEnabled = configProvider.getString(SSI_INJECTION_ENABLED);
    this.ssiInjectionForce =
        configProvider.getBoolean(SSI_INJECTION_FORCE, DEFAULT_SSI_INJECTION_FORCE);
    this.instrumentationSource =
        configProvider.getString(INSTRUMENTATION_SOURCE, DEFAULT_INSTRUMENTATION_SOURCE);

    this.apmTracingEnabled = configProvider.getBoolean(GeneralConfig.APM_TRACING_ENABLED, true);

    this.jdkSocketEnabled = configProvider.getBoolean(JDK_SOCKET_ENABLED, true);

    this.optimizedMapEnabled =
        configProvider.getBoolean(GeneralConfig.OPTIMIZED_MAP_ENABLED, false);
    this.spanBuilderReuseEnabled =
        configProvider.getBoolean(GeneralConfig.SPAN_BUILDER_REUSE_ENABLED, true);
    this.tagNameUtf8CacheSize =
        Math.max(configProvider.getInteger(GeneralConfig.TAG_NAME_UTF8_CACHE_SIZE, 128), 0);
    this.tagValueUtf8CacheSize =
        Math.max(configProvider.getInteger(GeneralConfig.TAG_VALUE_UTF8_CACHE_SIZE, 384), 0);

    int defaultStackTraceLengthLimit =
        instrumenterConfig.isCiVisibilityEnabled()
            ? 5000 // EVP limit
            : Integer.MAX_VALUE; // no effective limit (old behavior)
    this.stackTraceLengthLimit =
        configProvider.getInteger(STACK_TRACE_LENGTH_LIMIT, defaultStackTraceLengthLimit);

    this.rumInjectorConfig = parseRumConfig(configProvider);

    this.aiGuardEnabled = configProvider.getBoolean(AI_GUARD_ENABLED, DEFAULT_AI_GUARD_ENABLED);
    this.aiGuardEndpoint = configProvider.getString(AI_GUARD_ENDPOINT);
    this.aiGuardTimeout = configProvider.getInteger(AI_GUARD_TIMEOUT, DEFAULT_AI_GUARD_TIMEOUT);
    this.aiGuardMaxContentSize =
        configProvider.getInteger(AI_GUARD_MAX_CONTENT_SIZE, DEFAULT_AI_GUARD_MAX_CONTENT_SIZE);
    this.aiGuardMaxMessagesLength =
        configProvider.getInteger(
            AI_GUARD_MAX_MESSAGES_LENGTH, DEFAULT_AI_GUARD_MAX_MESSAGES_LENGTH);

    log.debug("New instance: {}", this);
  }

  private static boolean isValidUrl(String url) {
    if (url == null || url.isEmpty()) {
      return false;
    }
    try {
      new URL(url).toURI();
      return true;
    } catch (MalformedURLException | URISyntaxException ex) {
      log.error("Cannot parse URL '{}', skipping", url);
      return false;
    }
  }

  private RumInjectorConfig parseRumConfig(ConfigProvider configProvider) {
    if (!instrumenterConfig.isRumEnabled()) {
      return null;
    }
    try {
      return new RumInjectorConfig(
          configProvider.getString(RUM_APPLICATION_ID),
          configProvider.getString(RUM_CLIENT_TOKEN),
          configProvider.getString(RUM_SITE),
          configProvider.getString(RUM_SERVICE),
          configProvider.getString(RUM_ENVIRONMENT),
          configProvider.getInteger(RUM_MAJOR_VERSION, DEFAULT_RUM_MAJOR_VERSION),
          configProvider.getString(RUM_VERSION),
          configProvider.getBoolean(RUM_TRACK_USER_INTERACTION),
          configProvider.getBoolean(RUM_TRACK_RESOURCES),
          configProvider.getBoolean(RUM_TRACK_LONG_TASKS),
          configProvider.getEnum(RUM_DEFAULT_PRIVACY_LEVEL, PrivacyLevel.class, null),
          configProvider.getFloat(RUM_SESSION_SAMPLE_RATE),
          configProvider.getFloat(RUM_SESSION_REPLAY_SAMPLE_RATE),
          configProvider.getString(RUM_REMOTE_CONFIGURATION_ID));
    } catch (IllegalArgumentException e) {
      log.warn("Unable to configure RUM injection", e);
      return null;
    }
  }

  /**
   * Converts a list of packages in Jacoco exclusion format ({@code
   * my.package.*,my.other.package.*}) to list of package prefixes suitable for use with ASM ({@code
   * my/package/,my/other/package/})
   */
  public static String[] convertJacocoExclusionFormatToPackagePrefixes(List<String> packages) {
    return packages.stream()
        .map(s -> (s.endsWith("*") ? s.substring(0, s.length() - 1) : s).replace('.', '/'))
        .toArray(String[]::new);
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

  public String getApplicationKey() {
    return applicationKey;
  }

  public String getSite() {
    return site;
  }

  public String getHostName() {
    return HostNameHolder.hostName;
  }

  public Supplier<String> getHostNameSupplier() {
    return HostNameHolder::getHostName;
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

  public Set<String> getExperimentalFeaturesEnabled() {
    return experimentalFeaturesEnabled;
  }

  public boolean isExperimentalPropagateProcessTagsEnabled() {
    return experimentalPropagateProcessTagsEnabled;
  }

  public boolean isTraceEnabled() {
    return instrumenterConfig.isTraceEnabled();
  }

  public boolean isServiceDiscoveryEnabled() {
    return serviceDiscoveryEnabled;
  }

  public boolean isLongRunningTraceEnabled() {
    return longRunningTraceEnabled;
  }

  public long getLongRunningTraceInitialFlushInterval() {
    return longRunningTraceInitialFlushInterval;
  }

  public long getLongRunningTraceFlushInterval() {
    return longRunningTraceFlushInterval;
  }

  public float getTraceFlushIntervalSeconds() {
    return traceFlushIntervalSeconds;
  }

  public long getTracePostProcessingTimeout() {
    return tracePostProcessingTimeout;
  }

  public boolean isIntegrationSynapseLegacyOperationName() {
    return integrationSynapseLegacyOperationName;
  }

  public String getWriterType() {
    return writerType;
  }

  public boolean isInjectBaggageAsTagsEnabled() {
    return injectBaggageAsTagsEnabled;
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

  public boolean isForceClearTextHttpForIntakeClient() {
    return forceClearTextHttpForIntakeClient;
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

  public int getSpanAttributeSchemaVersion() {
    return spanAttributeSchemaVersion;
  }

  public boolean isPeerHostNameEnabled() {
    return peerHostNameEnabled;
  }

  public boolean isPeerServiceDefaultsEnabled() {
    return peerServiceDefaultsEnabled;
  }

  public Map<String, String> getPeerServiceComponentOverrides() {
    return peerServiceComponentOverrides;
  }

  public boolean isRemoveIntegrationServiceNamesEnabled() {
    return removeIntegrationServiceNamesEnabled;
  }

  public Map<String, String> getPeerServiceMapping() {
    return peerServiceMapping;
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

  public boolean isRequestHeaderTagsCommaAllowed() {
    return requestHeaderTagsCommaAllowed;
  }

  public Map<String, String> getBaggageMapping() {
    return baggageMapping;
  }

  public List<String> getTraceBaggageTagKeys() {
    return traceBaggageTagKeys;
  }

  public Map<String, String> getHttpServerPathResourceNameMapping() {
    return httpServerPathResourceNameMapping;
  }

  public Map<String, String> getHttpClientPathResourceNameMapping() {
    return httpClientPathResourceNameMapping;
  }

  public boolean getHttpResourceRemoveTrailingSlash() {
    return httpResourceRemoveTrailingSlash;
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

  public boolean isHttpServerDecodedResourcePreserveSpaces() {
    return httpServerDecodedResourcePreserveSpaces;
  }

  public boolean isHttpServerRouteBasedNaming() {
    return httpServerRouteBasedNaming;
  }

  public boolean isHttpClientTagQueryString() {
    return httpClientTagQueryString;
  }

  public boolean isHttpClientTagHeaders() {
    return httpClientTagHeaders;
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

  public boolean isDbClientSplitByHost() {
    return dbClientSplitByHost;
  }

  public Set<String> getSplitByTags() {
    return splitByTags;
  }

  public boolean isJeeSplitByDeployment() {
    return jeeSplitByDeployment;
  }

  public int getScopeDepthLimit() {
    return scopeDepthLimit;
  }

  public boolean isScopeStrictMode() {
    return scopeStrictMode;
  }

  public int getScopeIterationKeepAlive() {
    return scopeIterationKeepAlive;
  }

  public int getPartialFlushMinSpans() {
    return partialFlushMinSpans;
  }

  public int getTraceKeepLatencyThreshold() {
    return traceKeepLatencyThreshold;
  }

  public boolean isTraceKeepLatencyThresholdEnabled() {
    return traceKeepLatencyThresholdEnabled;
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

  public boolean isTracePropagationStyleB3PaddingEnabled() {
    return tracePropagationStyleB3PaddingEnabled;
  }

  public Set<TracePropagationStyle> getTracePropagationStylesToExtract() {
    return tracePropagationStylesToExtract;
  }

  public Set<TracePropagationStyle> getTracePropagationStylesToInject() {
    return tracePropagationStylesToInject;
  }

  public TracePropagationBehaviorExtract getTracePropagationBehaviorExtract() {
    return tracePropagationBehaviorExtract;
  }

  public boolean isTracePropagationExtractFirst() {
    return tracePropagationExtractFirst;
  }

  public boolean isInferredProxyPropagationEnabled() {
    return traceInferredProxyEnabled;
  }

  public boolean isBaggageExtract() {
    return tracePropagationStylesToExtract.contains(TracePropagationStyle.BAGGAGE);
  }

  public boolean isBaggageInject() {
    return tracePropagationStylesToInject.contains(TracePropagationStyle.BAGGAGE);
  }

  public boolean isBaggagePropagationEnabled() {
    return isBaggageInject() || isBaggageExtract();
  }

  public int getTraceBaggageMaxItems() {
    return traceBaggageMaxItems;
  }

  public int getTraceBaggageMaxBytes() {
    return traceBaggageMaxBytes;
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

  public Integer getStatsDClientQueueSize() {
    return statsDClientQueueSize;
  }

  public Integer getStatsDClientSocketBuffer() {
    return statsDClientSocketBuffer;
  }

  public Integer getStatsDClientSocketTimeout() {
    return statsDClientSocketTimeout;
  }

  public boolean isRuntimeMetricsEnabled() {
    return runtimeMetricsEnabled;
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
    // When ASM Standalone Billing is enabled metrics should be disabled
    return tracerMetricsEnabled && isApmTracingEnabled();
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
    return logsInjectionEnabled;
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

  public boolean isTraceGitMetadataEnabled() {
    return traceGitMetadataEnabled;
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
    if (Platform.isNativeImage()) {
      if (!instrumenterConfig.isProfilingEnabled() && profilingEnabled.isActive()) {
        log.warn(
            "Profiling was not enabled during the native image build. "
                + "Please set DD_PROFILING_ENABLED=true in your native image build configuration if you want"
                + "to use profiling.");
      }
    }
    return profilingEnabled.isActive() && instrumenterConfig.isProfilingEnabled();
  }

  public boolean isProfilingTimelineEventsEnabled() {
    return timelineEventsEnabled;
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

  public int getProfilingBackPressureSampleLimit() {
    return profilingBackPressureSampleLimit;
  }

  public boolean isProfilingBackPressureSamplingEnabled() {
    return profilingBackPressureEnabled;
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

  public boolean isProfilingRecordExceptionMessage() {
    return profilingRecordExceptionMessage;
  }

  public boolean isDatadogProfilerEnabled() {
    return isProfilingEnabled() && isDatadogProfilerEnabled;
  }

  public static boolean isDatadogProfilerEnablementOverridden() {
    // old non-LTS versions without important backports
    // also, we have no windows binaries
    return OperatingSystem.isWindows()
        || isJavaVersion(18)
        || isJavaVersion(16)
        || isJavaVersion(15)
        || isJavaVersion(14)
        || isJavaVersion(13)
        || isJavaVersion(12)
        || isJavaVersion(10)
        || isJavaVersion(9);
  }

  public static boolean isDatadogProfilerSafeInCurrentEnvironment() {
    // don't want to put this logic (which will evolve) in the public ProfilingConfig, and can't
    // access Platform there
    if (!JavaVirtualMachine.isJ9() && isJavaVersion(8)) {
      String arch = SystemProperties.get("os.arch");
      if ("aarch64".equalsIgnoreCase(arch) || "arm64".equalsIgnoreCase(arch)) {
        return false;
      }
    }
    if (JavaVirtualMachine.isGraalVM()) {
      // let's be conservative about GraalVM and require opt-in from the users
      return false;
    }
    boolean result = false;
    if (JavaVirtualMachine.isJ9()) {
      // OpenJ9 will activate only JVMTI GetAllStackTraces based profiling which is safe
      result = true;
    } else {
      // JDK 18 is missing ASGCT fixes, so we can't use it
      if (!isJavaVersion(18)) {
        result =
            isJavaVersionAtLeast(17, 0, 5)
                || (isJavaVersion(11) && isJavaVersionAtLeast(11, 0, 17))
                || (isJavaVersion(8) && isJavaVersionAtLeast(8, 0, 352));
      }
    }
    return result;
  }

  public boolean isCrashTrackingAgentless() {
    return crashTrackingAgentless;
  }

  public boolean isTelemetryEnabled() {
    return instrumenterConfig.isTelemetryEnabled();
  }

  public float getTelemetryHeartbeatInterval() {
    return telemetryHeartbeatInterval;
  }

  public long getTelemetryExtendedHeartbeatInterval() {
    return telemetryExtendedHeartbeatInterval;
  }

  public float getTelemetryMetricsInterval() {
    return telemetryMetricsInterval;
  }

  public boolean isTelemetryDependencyServiceEnabled() {
    return isTelemetryDependencyServiceEnabled;
  }

  public boolean isTelemetryMetricsEnabled() {
    return telemetryMetricsEnabled;
  }

  public boolean isTelemetryLogCollectionEnabled() {
    return isTelemetryLogCollectionEnabled;
  }

  public int getTelemetryDependencyResolutionQueueSize() {
    return telemetryDependencyResolutionQueueSize;
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

  public UserIdCollectionMode getAppSecUserIdCollectionMode() {
    return appSecUserIdCollectionMode;
  }

  public boolean isApiSecurityEnabled() {
    return apiSecurityEnabled;
  }

  public float getApiSecuritySampleDelay() {
    return apiSecuritySampleDelay;
  }

  public int getApiSecurityEndpointCollectionMessageLimit() {
    return apiSecurityEndpointCollectionMessageLimit;
  }

  public int getApiSecurityMaxDownstreamRequestBodyAnalysis() {
    return apiSecurityMaxDownstreamRequestBodyAnalysis;
  }

  public double getApiSecurityDownstreamRequestAnalysisSampleRate() {
    return apiSecurityDownstreamRequestAnalysisSampleRate;
  }

  public boolean isApiSecurityEndpointCollectionEnabled() {
    return apiSecurityEndpointCollectionEnabled;
  }

  public ProductActivation getIastActivation() {
    return instrumenterConfig.getIastActivation();
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

  public Verbosity getIastTelemetryVerbosity() {
    return isTelemetryEnabled() ? iastTelemetryVerbosity : Verbosity.OFF;
  }

  public boolean isIastRedactionEnabled() {
    return iastRedactionEnabled;
  }

  public String getIastRedactionNamePattern() {
    return iastRedactionNamePattern;
  }

  public String getIastRedactionValuePattern() {
    return iastRedactionValuePattern;
  }

  public int getIastTruncationMaxValueLength() {
    return iastTruncationMaxValueLength;
  }

  public int getIastMaxRangeCount() {
    return iastMaxRangeCount;
  }

  public boolean isIastStacktraceLeakSuppress() {
    return iastStacktraceLeakSuppress;
  }

  public IastContext.Mode getIastContextMode() {
    return iastContextMode;
  }

  public boolean isIastHardcodedSecretEnabled() {
    return iastHardcodedSecretEnabled;
  }

  public boolean isIastSourceMappingEnabled() {
    return iastSourceMappingEnabled;
  }

  public int getIastSourceMappingMaxSize() {
    return iastSourceMappingMaxSize;
  }

  public IastDetectionMode getIastDetectionMode() {
    return iastDetectionMode;
  }

  public boolean isIastAnonymousClassesEnabled() {
    return iastAnonymousClassesEnabled;
  }

  public boolean isIastStackTraceEnabled() {
    return iastStackTraceEnabled;
  }

  public boolean isIastExperimentalPropagationEnabled() {
    return iastExperimentalPropagationEnabled;
  }

  public String getIastSecurityControlsConfiguration() {
    return iastSecurityControlsConfiguration;
  }

  public int getIastDbRowsToTaint() {
    return iastDbRowsToTaint;
  }

  public boolean isLlmObsEnabled() {
    return instrumenterConfig.isLlmObsEnabled();
  }

  public boolean isLlmObsAgentlessEnabled() {
    return llmObsAgentlessEnabled;
  }

  public String getLlMObsAgentlessUrl() {
    return llmObsAgentlessUrl;
  }

  public String getLlmObsMlApp() {
    return llmObsMlApp;
  }

  public boolean isCiVisibilityEnabled() {
    return instrumenterConfig.isCiVisibilityEnabled();
  }

  public boolean isUsmEnabled() {
    return instrumenterConfig.isUsmEnabled();
  }

  public boolean isCiVisibilityTraceSanitationEnabled() {
    return ciVisibilityTraceSanitationEnabled;
  }

  public boolean isCiVisibilityAgentlessEnabled() {
    return ciVisibilityAgentlessEnabled;
  }

  public String getCiVisibilityAgentlessUrl() {
    return ciVisibilityAgentlessUrl;
  }

  public String getCiVisibilityIntakeAgentlessUrl() {
    return ciVisibilityIntakeAgentlessUrl;
  }

  public boolean isCiVisibilitySourceDataEnabled() {
    return ciVisibilitySourceDataEnabled;
  }

  public boolean isCiVisibilityBuildInstrumentationEnabled() {
    return ciVisibilityBuildInstrumentationEnabled;
  }

  public String getCiVisibilityAgentJarUri() {
    return ciVisibilityAgentJarUri;
  }

  public File getCiVisibilityAgentJarFile() {
    if (ciVisibilityAgentJarUri == null || ciVisibilityAgentJarUri.isEmpty()) {
      throw new IllegalArgumentException("Agent JAR URI is not set in config");
    }

    try {
      URI agentJarUri = new URI(ciVisibilityAgentJarUri);
      return new File(agentJarUri);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Malformed agent JAR URI: " + ciVisibilityAgentJarUri, e);
    }
  }

  public boolean isCiVisibilityAutoConfigurationEnabled() {
    return ciVisibilityAutoConfigurationEnabled;
  }

  public String getCiVisibilityAdditionalChildProcessJvmArgs() {
    return ciVisibilityAdditionalChildProcessJvmArgs;
  }

  public boolean isCiVisibilityCompilerPluginAutoConfigurationEnabled() {
    return ciVisibilityCompilerPluginAutoConfigurationEnabled;
  }

  public boolean isCiVisibilityCodeCoverageEnabled() {
    return ciVisibilityCodeCoverageEnabled;
  }

  /** @return {@code true} if code coverage line-granularity is explicitly enabled */
  public boolean isCiVisibilityCoverageLinesEnabled() {
    return ciVisibilityCoverageLinesEnabled != null && ciVisibilityCoverageLinesEnabled;
  }

  /** @return {@code true} if code coverage line-granularity is explicitly disabled */
  public boolean isCiVisibilityCoverageLinesDisabled() {
    return ciVisibilityCoverageLinesEnabled != null && !ciVisibilityCoverageLinesEnabled;
  }

  public String getCiVisibilityCodeCoverageReportDumpDir() {
    return ciVisibilityCodeCoverageReportDumpDir;
  }

  public String getCiVisibilityCompilerPluginVersion() {
    return ciVisibilityCompilerPluginVersion;
  }

  public String getCiVisibilityJacocoPluginVersion() {
    return ciVisibilityJacocoPluginVersion;
  }

  public boolean isCiVisibilityJacocoPluginVersionProvided() {
    return ciVisibilityJacocoPluginVersionProvided;
  }

  public List<String> getCiVisibilityCodeCoverageIncludes() {
    return ciVisibilityCodeCoverageIncludes;
  }

  public List<String> getCiVisibilityCodeCoverageExcludes() {
    return ciVisibilityCodeCoverageExcludes;
  }

  public String[] getCiVisibilityCodeCoverageIncludedPackages() {
    return Arrays.copyOf(
        ciVisibilityCodeCoverageIncludedPackages, ciVisibilityCodeCoverageIncludedPackages.length);
  }

  public String[] getCiVisibilityCodeCoverageExcludedPackages() {
    return Arrays.copyOf(
        ciVisibilityCodeCoverageExcludedPackages, ciVisibilityCodeCoverageExcludedPackages.length);
  }

  public List<String> getCiVisibilityJacocoGradleSourceSets() {
    return ciVisibilityJacocoGradleSourceSets;
  }

  public boolean isCiVisibilityCodeCoverageReportUploadEnabled() {
    return ciVisibilityCodeCoverageReportUploadEnabled;
  }

  public Integer getCiVisibilityDebugPort() {
    return ciVisibilityDebugPort;
  }

  public boolean isCiVisibilityGitClientEnabled() {
    return ciVisibilityGitClientEnabled;
  }

  public boolean isCiVisibilityGitUploadEnabled() {
    return ciVisibilityGitUploadEnabled;
  }

  public boolean isCiVisibilityGitUnshallowEnabled() {
    return ciVisibilityGitUnshallowEnabled;
  }

  public boolean isCiVisibilityGitUnshallowDefer() {
    return ciVisibilityGitUnshallowDefer;
  }

  public long getCiVisibilityGitCommandTimeoutMillis() {
    return ciVisibilityGitCommandTimeoutMillis;
  }

  public long getCiVisibilityBackendApiTimeoutMillis() {
    return ciVisibilityBackendApiTimeoutMillis;
  }

  public long getCiVisibilityGitUploadTimeoutMillis() {
    return ciVisibilityGitUploadTimeoutMillis;
  }

  public String getCiVisibilityGitRemoteName() {
    return ciVisibilityGitRemoteName;
  }

  public int getCiVisibilitySignalServerPort() {
    return ciVisibilitySignalServerPort;
  }

  public int getCiVisibilitySignalClientTimeoutMillis() {
    return ciVisibilitySignalClientTimeoutMillis;
  }

  public String getCiVisibilitySignalServerHost() {
    return ciVisibilitySignalServerHost;
  }

  public boolean isCiVisibilityItrEnabled() {
    return ciVisibilityItrEnabled;
  }

  public boolean isCiVisibilityTestSkippingEnabled() {
    return ciVisibilityTestSkippingEnabled;
  }

  public boolean isCiVisibilityCiProviderIntegrationEnabled() {
    return ciVisibilityCiProviderIntegrationEnabled;
  }

  public boolean isCiVisibilityRepoIndexDuplicateKeyCheckEnabled() {
    return ciVisibilityRepoIndexDuplicateKeyCheckEnabled;
  }

  public boolean isCiVisibilityRepoIndexFollowSymlinks() {
    return ciVisibilityRepoIndexFollowSymlinks;
  }

  public int getCiVisibilityExecutionSettingsCacheSize() {
    return ciVisibilityExecutionSettingsCacheSize;
  }

  public int getCiVisibilityJvmInfoCacheSize() {
    return ciVisibilityJvmInfoCacheSize;
  }

  public int getCiVisibilityCoverageRootPackagesLimit() {
    return ciVisibilityCoverageRootPackagesLimit;
  }

  public String getCiVisibilityInjectedTracerVersion() {
    return ciVisibilityInjectedTracerVersion;
  }

  public List<String> getCiVisibilityResourceFolderNames() {
    return ciVisibilityResourceFolderNames;
  }

  public boolean isCiVisibilityFlakyRetryEnabled() {
    return ciVisibilityFlakyRetryEnabled;
  }

  public boolean isCiVisibilityImpactedTestsDetectionEnabled() {
    return ciVisibilityImpactedTestsDetectionEnabled;
  }

  public boolean isCiVisibilityKnownTestsRequestEnabled() {
    return ciVisibilityKnownTestsRequestEnabled;
  }

  public boolean isCiVisibilityFlakyRetryOnlyKnownFlakes() {
    return ciVisibilityFlakyRetryOnlyKnownFlakes;
  }

  public boolean isCiVisibilityEarlyFlakeDetectionEnabled() {
    return ciVisibilityEarlyFlakeDetectionEnabled;
  }

  public int getCiVisibilityEarlyFlakeDetectionLowerLimit() {
    return ciVisibilityEarlyFlakeDetectionLowerLimit;
  }

  /**
   * @return {@code true} if any of the features that require CI Visibility execution policies are
   *     enabled. This is used to enable corresponding instrumentations only when they're needed,
   *     avoiding unnecessary overhead.
   */
  public boolean isCiVisibilityExecutionPoliciesEnabled() {
    return ciVisibilityFlakyRetryEnabled
        || ciVisibilityEarlyFlakeDetectionEnabled
        || ciVisibilityTestManagementEnabled;
  }

  public boolean isCiVisibilityScalatestForkMonitorEnabled() {
    return ciVisibilityScalatestForkMonitorEnabled;
  }

  public int getCiVisibilityFlakyRetryCount() {
    return ciVisibilityFlakyRetryCount;
  }

  public int getCiVisibilityTotalFlakyRetryCount() {
    return ciVisibilityTotalFlakyRetryCount;
  }

  public String getCiVisibilitySessionName() {
    return ciVisibilitySessionName;
  }

  public String getCiVisibilityModuleName() {
    return ciVisibilityModuleName;
  }

  public String getCiVisibilityTestCommand() {
    return ciVisibilityTestCommand;
  }

  public boolean isCiVisibilityTelemetryEnabled() {
    return ciVisibilityTelemetryEnabled;
  }

  public long getCiVisibilityRumFlushWaitMillis() {
    return ciVisibilityRumFlushWaitMillis;
  }

  public boolean isCiVisibilityAutoInjected() {
    return ciVisibilityAutoInjected;
  }

  public String getCiVisibilityTestOrder() {
    return ciVisibilityTestOrder;
  }

  public boolean isCiVisibilityTestManagementEnabled() {
    return ciVisibilityTestManagementEnabled;
  }

  public Integer getCiVisibilityTestManagementAttemptToFixRetries() {
    return ciVisibilityTestManagementAttemptToFixRetries;
  }

  public boolean isCiVisibilityFailedTestReplayEnabled() {
    return ciVisibilityFailedTestReplayEnabled;
  }

  public String getGitPullRequestBaseBranch() {
    return gitPullRequestBaseBranch;
  }

  public String getGitPullRequestBaseBranchSha() {
    return gitPullRequestBaseBranchSha;
  }

  public String getGitCommitHeadSha() {
    return gitCommitHeadSha;
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

  public float getRemoteConfigPollIntervalSeconds() {
    return remoteConfigPollIntervalSeconds;
  }

  public String getRemoteConfigTargetsKeyId() {
    return remoteConfigTargetsKeyId;
  }

  public String getRemoteConfigTargetsKey() {
    return remoteConfigTargetsKey;
  }

  public int getRemoteConfigMaxExtraServices() {
    return remoteConfigMaxExtraServices;
  }

  public boolean isDynamicInstrumentationEnabled() {
    return dynamicInstrumentationEnabled;
  }

  public int getDynamicInstrumentationUploadTimeout() {
    return dynamicInstrumentationUploadTimeout;
  }

  public int getDynamicInstrumentationUploadFlushInterval() {
    return dynamicInstrumentationUploadFlushInterval;
  }

  public boolean isDynamicInstrumentationClassFileDumpEnabled() {
    return dynamicInstrumentationClassFileDumpEnabled;
  }

  public int getDynamicInstrumentationPollInterval() {
    return dynamicInstrumentationPollInterval;
  }

  public int getDynamicInstrumentationDiagnosticsInterval() {
    return dynamicInstrumentationDiagnosticsInterval;
  }

  public boolean isDynamicInstrumentationMetricsEnabled() {
    return dynamicInstrumentationMetricEnabled;
  }

  public int getDynamicInstrumentationUploadBatchSize() {
    return dynamicInstrumentationUploadBatchSize;
  }

  public long getDynamicInstrumentationMaxPayloadSize() {
    return dynamicInstrumentationMaxPayloadSize;
  }

  public boolean isDynamicInstrumentationVerifyByteCode() {
    return dynamicInstrumentationVerifyByteCode;
  }

  public String getDynamicInstrumentationInstrumentTheWorld() {
    return dynamicInstrumentationInstrumentTheWorld;
  }

  public String getDynamicInstrumentationExcludeFiles() {
    return dynamicInstrumentationExcludeFiles;
  }

  public String getDynamicInstrumentationIncludeFiles() {
    return dynamicInstrumentationIncludeFiles;
  }

  public int getDynamicInstrumentationCaptureTimeout() {
    return dynamicInstrumentationCaptureTimeout;
  }

  public boolean isSymbolDatabaseEnabled() {
    return symbolDatabaseEnabled;
  }

  public boolean isSymbolDatabaseForceUpload() {
    return symbolDatabaseForceUpload;
  }

  public int getSymbolDatabaseFlushThreshold() {
    return symbolDatabaseFlushThreshold;
  }

  public boolean isSymbolDatabaseCompressed() {
    return symbolDatabaseCompressed;
  }

  public boolean isDebuggerExceptionEnabled() {
    return debuggerExceptionEnabled;
  }

  public int getDebuggerMaxExceptionPerSecond() {
    return debuggerMaxExceptionPerSecond;
  }

  public boolean isDebuggerExceptionOnlyLocalRoot() {
    return debuggerExceptionOnlyLocalRoot;
  }

  public boolean isDebuggerExceptionCaptureIntermediateSpansEnabled() {
    return debuggerExceptionCaptureIntermediateSpansEnabled;
  }

  public int getDebuggerExceptionMaxCapturedFrames() {
    return debuggerExceptionMaxCapturedFrames;
  }

  public int getDebuggerExceptionCaptureInterval() {
    return debuggerExceptionCaptureInterval;
  }

  public boolean isDebuggerCodeOriginEnabled() {
    return debuggerCodeOriginEnabled;
  }

  public int getDebuggerCodeOriginMaxUserFrames() {
    return debuggerCodeOriginMaxUserFrames;
  }

  public boolean isDistributedDebuggerEnabled() {
    return distributedDebuggerEnabled;
  }

  public boolean isDebuggerSourceFileTrackingEnabled() {
    return debuggerSourceFileTrackingEnabled;
  }

  public Set<String> getThirdPartyIncludes() {
    return debuggerThirdPartyIncludes;
  }

  public Set<String> getThirdPartyExcludes() {
    return debuggerThirdPartyExcludes;
  }

  public Set<String> getThirdPartyShadingIdentifiers() {
    return debuggerShadingIdentifiers;
  }

  private String getFinalDebuggerBaseUrl() {
    if (agentUrl.startsWith("unix:")) {
      // provide placeholder agent URL, in practice we'll be tunnelling over UDS
      return "http://" + agentHost + ":" + agentPort;
    } else {
      return agentUrl;
    }
  }

  public String getFinalDebuggerSnapshotUrl() {
    if (Strings.isNotBlank(dynamicInstrumentationSnapshotUrl)) {
      return dynamicInstrumentationSnapshotUrl;
    } else if (isCiVisibilityAgentlessEnabled()) {
      return Intake.LOGS.getAgentlessUrl(this) + "logs";
    } else {
      throw new IllegalArgumentException("Cannot find snapshot endpoint on datadog agent");
    }
  }

  public String getFinalDebuggerSymDBUrl() {
    if (isCiVisibilityAgentlessEnabled()) {
      return Intake.LOGS.getAgentlessUrl(this) + "logs";
    } else {
      return getFinalDebuggerBaseUrl() + "/symdb/v1/input";
    }
  }

  public String getDynamicInstrumentationProbeFile() {
    return dynamicInstrumentationProbeFile;
  }

  public String getDynamicInstrumentationRedactedIdentifiers() {
    return dynamicInstrumentationRedactedIdentifiers;
  }

  public Set<String> getDynamicInstrumentationRedactionExcludedIdentifiers() {
    return dynamicInstrumentationRedactionExcludedIdentifiers;
  }

  public String getDynamicInstrumentationRedactedTypes() {
    return dynamicInstrumentationRedactedTypes;
  }

  public int getDynamicInstrumentationLocalVarHoistingLevel() {
    return dynamicInstrumentationLocalVarHoistingLevel;
  }

  public boolean isAwsPropagationEnabled() {
    return awsPropagationEnabled;
  }

  public boolean isSqsPropagationEnabled() {
    return sqsPropagationEnabled;
  }

  public boolean isSqsBodyPropagationEnabled() {
    return sqsBodyPropagationEnabled;
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

  public int getJmsUnacknowledgedMaxAge() {
    return jmsUnacknowledgedMaxAge;
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

  public boolean isResilience4jMeasuredEnabled() {
    return resilience4jMeasuredEnabled;
  }

  public boolean isResilience4jTagMetricsEnabled() {
    return resilience4jTagMetricsEnabled;
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

  public String getLogLevel() {
    return logLevel;
  }

  public boolean isDebugEnabled() {
    return debugEnabled;
  }

  public boolean isTriageEnabled() {
    return triageEnabled;
  }

  public String getTriageReportTrigger() {
    return triageReportTrigger;
  }

  public String getTriageReportDir() {
    return triageReportDir;
  }

  public boolean isStartupLogsEnabled() {
    return startupLogsEnabled;
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

  public boolean isAwsServerless() {
    return awsServerless;
  }

  public boolean isDataStreamsEnabled() {
    return dataStreamsEnabled;
  }

  public float getDataStreamsBucketDurationSeconds() {
    return dataStreamsBucketDurationSeconds;
  }

  public long getDataStreamsBucketDurationNanoseconds() {
    // Rounds to the nearest millisecond before converting to nanos
    int milliseconds = Math.round(dataStreamsBucketDurationSeconds * 1000);
    return TimeUnit.MILLISECONDS.toNanos(milliseconds);
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

  public int getDogsStatsDPort() {
    return dogStatsDPort;
  }

  public String getConfigFileStatus() {
    return configFileStatus;
  }

  public IdGenerationStrategy getIdGenerationStrategy() {
    return idGenerationStrategy;
  }

  public boolean isTrace128bitTraceIdGenerationEnabled() {
    return trace128bitTraceIdGenerationEnabled;
  }

  public boolean isLogs128bitTraceIdEnabled() {
    return logs128bitTraceIdEnabled;
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

  public boolean isCassandraKeyspaceStatementExtractionEnabled() {
    return cassandraKeyspaceStatementExtractionEnabled;
  }

  public boolean isCouchbaseInternalSpansEnabled() {
    return couchbaseInternalSpansEnabled;
  }

  public boolean isElasticsearchBodyEnabled() {
    return elasticsearchBodyEnabled;
  }

  public boolean isElasticsearchParamsEnabled() {
    return elasticsearchParamsEnabled;
  }

  public boolean isElasticsearchBodyAndParamsEnabled() {
    return elasticsearchBodyAndParamsEnabled;
  }

  public boolean isSparkTaskHistogramEnabled() {
    return sparkTaskHistogramEnabled;
  }

  public boolean useSparkAppNameAsService() {
    return sparkAppNameAsService;
  }

  public boolean isJaxRsExceptionAsErrorEnabled() {
    return jaxRsExceptionAsErrorsEnabled;
  }

  public boolean isAxisPromoteResourceName() {
    return axisPromoteResourceName;
  }

  public boolean isWebsocketMessagesInheritSampling() {
    return websocketMessagesInheritSampling;
  }

  public boolean isWebsocketMessagesSeparateTraces() {
    return websocketMessagesSeparateTraces;
  }

  public boolean isWebsocketTagSessionId() {
    return websocketTagSessionId;
  }

  public boolean isDataJobsEnabled() {
    return dataJobsEnabled;
  }

  public boolean isDataJobsOpenLineageEnabled() {
    return dataJobsOpenLineageEnabled;
  }

  public boolean isDataJobsOpenLineageTimeoutEnabled() {
    return dataJobsOpenLineageTimeoutEnabled;
  }

  public boolean isApmTracingEnabled() {
    return apmTracingEnabled;
  }

  public boolean isJdkSocketEnabled() {
    return jdkSocketEnabled;
  }

  public boolean isOptimizedMapEnabled() {
    return optimizedMapEnabled;
  }

  public boolean isSpanBuilderReuseEnabled() {
    return spanBuilderReuseEnabled;
  }

  public int getTagNameUtf8CacheSize() {
    return tagNameUtf8CacheSize;
  }

  public int getTagValueUtf8CacheSize() {
    return tagValueUtf8CacheSize;
  }

  public int getStackTraceLengthLimit() {
    return stackTraceLengthLimit;
  }

  /** @return A map of tags to be applied only to the local application root span. */
  public TagMap getLocalRootSpanTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();

    final TagMap result = TagMap.fromMap(runtimeTags);
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    result.put(SCHEMA_VERSION_TAG_KEY, SpanNaming.instance().version());
    result.put(DDTags.PROFILING_ENABLED, isProfilingEnabled() ? 1 : 0);
    if (!isApmTracingEnabled()) {
      result.put(APM_ENABLED, 0);
    }

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

    return result.freeze();
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

  public CiVisibilityWellKnownTags getCiVisibilityWellKnownTags() {
    return new CiVisibilityWellKnownTags(
        getRuntimeId(),
        getEnv(),
        LANGUAGE_TAG_VALUE,
        SystemProperties.get("java.runtime.name"),
        SystemProperties.get("java.version"),
        SystemProperties.get("java.vendor"),
        SystemProperties.get("os.arch"),
        SystemProperties.get("os.name"),
        SystemProperties.get("os.version"),
        isServiceNameSetByUser() ? "true" : "false");
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
    if (azureAppServices) {
      result.putAll(getAzureAppServicesTags());
    }
    return Collections.unmodifiableMap(result);
  }

  public Map<String, String> getMergedCrashTrackingTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final String host = getHostName();
    final Map<String, String> result =
        newHashMap(
            getGlobalTags().size()
                + crashTrackingTags.size()
                + jmxTags.size()
                + runtimeTags.size()
                + 3 /* for serviceName and host and language */);
    result.put(HOST_TAG, host); // Host goes first to allow to override it
    result.putAll(getGlobalTags());
    result.putAll(jmxTags);
    result.putAll(crashTrackingTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may chose to override it.
    result.put(SERVICE_TAG, serviceName);
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    return Collections.unmodifiableMap(result);
  }

  public String getDefaultTelemetryUrl() {
    String site = getSite();
    String prefix = "";
    if (site.endsWith("datad0g.com")) {
      prefix = "all-http-intake.logs.";
    } else if (site.endsWith("datadoghq.com") || site.endsWith("datadoghq.eu")) {
      prefix = "instrumentation-telemetry-intake.";
    }
    return "https://" + prefix + site + "/api/v2/apmtelemetry";
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
    int plusIndex = websiteOwner == null ? -1 : websiteOwner.indexOf('+');

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
      resourceId = resourceId.toLowerCase(Locale.ROOT);
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

  private int schemaVersionFromConfig() {
    String defaultVersion;
    // use v1 so Azure Functions operation name is consistent with that of other tracers
    if (azureFunctions) {
      defaultVersion = "v1";
    } else {
      defaultVersion = "v" + SpanNaming.SCHEMA_MIN_VERSION;
    }
    String versionStr = configProvider.getString(TRACE_SPAN_ATTRIBUTE_SCHEMA, defaultVersion);
    Matcher matcher = Pattern.compile("^v?(0|[1-9]\\d*)$").matcher(versionStr);
    int parsedVersion = -1;
    if (matcher.matches()) {
      parsedVersion = Integer.parseInt(matcher.group(1));
    }
    if (parsedVersion < SpanNaming.SCHEMA_MIN_VERSION
        || parsedVersion > SpanNaming.SCHEMA_MAX_VERSION) {
      log.warn(
          "Invalid attribute schema version {} invalid or out of range [v{}, v{}]. Defaulting to v{}",
          versionStr,
          SpanNaming.SCHEMA_MIN_VERSION,
          SpanNaming.SCHEMA_MAX_VERSION,
          SpanNaming.SCHEMA_MIN_VERSION);
      parsedVersion = SpanNaming.SCHEMA_MIN_VERSION;
    }
    return parsedVersion;
  }

  public String getFinalProfilingUrl() {
    if (profilingUrl != null) {
      // when profilingUrl is set we use it regardless of apiKey/agentless config
      return profilingUrl;
    } else if (profilingAgentless) {
      // when agentless profiling is turned on we send directly to our intake
      return "https://intake.profile." + site + "/api/v2/profile";
    } else {
      // When profilingUrl and agentless are not set we send to the dd trace agent running locally
      // However, there are two gotchas:
      // - the agentHost, agentPort split will trip on IPv6 addresses because of the colon -> we
      // need to use the agentUrl
      // - but the agentUrl can be unix socket and OKHttp doesn't support that so we fall back to
      // http
      //
      // There is some magic behind the scenes where the http url will be converted to UDS if the
      // target is a unix socket only
      String baseUrl =
          agentUrl.startsWith("unix:") ? "http://" + agentHost + ":" + agentPort : agentUrl;
      return baseUrl + "/profiling/v1/input";
    }
  }

  public String getFinalLLMObsUrl() {
    if (llmObsAgentlessEnabled) {
      return "https://llmobs-intake." + site + "/api/v2/llmobs";
    }
    return null;
  }

  public String getFinalCrashTrackingTelemetryUrl() {
    if (crashTrackingAgentless) {
      // when agentless crashTracking is turned on we send directly to our intake
      return getDefaultTelemetryUrl();
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
        configProvider.getBoolean(
            "trace." + name.toLowerCase(Locale.ROOT) + ".enabled", defaultEnabled);
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

  public boolean isSqsLegacyTracingEnabled() {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && isLegacyTracingEnabled(true, "sqs");
  }

  public boolean isAwsLegacyTracingEnabled() {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && isLegacyTracingEnabled(false, "aws-sdk");
  }

  public boolean isJmsLegacyTracingEnabled() {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && isLegacyTracingEnabled(true, "jms");
  }

  public boolean isKafkaLegacyTracingEnabled() {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && isLegacyTracingEnabled(true, "kafka");
  }

  public boolean isGooglePubSubLegacyTracingEnabled() {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && isLegacyTracingEnabled(true, "google-pubsub");
  }

  public boolean isTimeInQueueEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && configProvider.isEnabled(
            Arrays.asList(integrationNames), "", ".time-in-queue.enabled", defaultEnabled);
  }

  public boolean isAddSpanPointers(final String integrationName) {
    return configProvider.isEnabled(
        Collections.singletonList(ADD_SPAN_POINTERS),
        integrationName,
        "",
        DEFAULT_ADD_SPAN_POINTERS);
  }

  public boolean isEnabled(
      final boolean defaultEnabled, final String settingName, String settingSuffix) {
    return configProvider.isEnabled(
        Collections.singletonList(settingName), "", settingSuffix, defaultEnabled);
  }

  public long getDependecyResolutionPeriodMillis() {
    return dependecyResolutionPeriodMillis;
  }

  public boolean isDbmInjectSqlBaseHash() {
    return dbmInjectSqlBaseHash;
  }

  public boolean isDbmTracePreparedStatements() {
    return dbmTracePreparedStatements;
  }

  public String getDbmPropagationMode() {
    return dbmPropagationMode;
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
    return configProvider.getBoolean(SAMPLING_MECHANISM_VALIDATION_DISABLED, false);
  }

  public <T extends Enum<T>> T getEnumValue(
      final String name, final Class<T> type, final T defaultValue) {
    return configProvider.getEnum(name, type, defaultValue);
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

  public boolean isTelemetryDebugRequestsEnabled() {
    return telemetryDebugRequestsEnabled;
  }

  public boolean isAgentlessLogSubmissionEnabled() {
    return agentlessLogSubmissionEnabled;
  }

  public int getAgentlessLogSubmissionQueueSize() {
    return agentlessLogSubmissionQueueSize;
  }

  public String getAgentlessLogSubmissionLevel() {
    return agentlessLogSubmissionLevel;
  }

  public String getAgentlessLogSubmissionUrl() {
    return agentlessLogSubmissionUrl;
  }

  public String getAgentlessLogSubmissionProduct() {
    return agentlessLogSubmissionProduct;
  }

  public boolean isAppSecScaEnabled() {
    return appSecScaEnabled != null && appSecScaEnabled;
  }

  public boolean isAppSecRaspEnabled() {
    return appSecRaspEnabled;
  }

  public boolean isAppSecStackTraceEnabled() {
    return appSecStackTraceEnabled;
  }

  public int getAppSecMaxStackTraces() {
    return appSecMaxStackTraces;
  }

  public int getAppSecMaxStackTraceDepth() {
    return appSecMaxStackTraceDepth;
  }

  public int getAppSecBodyParsingSizeLimit() {
    return appSecBodyParsingSizeLimit;
  }

  public boolean isCloudPayloadTaggingEnabledFor(String serviceName) {
    return cloudPayloadTaggingServices.contains(serviceName);
  }

  public boolean isCloudPayloadTaggingEnabled() {
    return isCloudRequestPayloadTaggingEnabled() || isCloudResponsePayloadTaggingEnabled();
  }

  public List<String> getCloudRequestPayloadTagging() {
    return cloudRequestPayloadTagging == null
        ? Collections.emptyList()
        : cloudRequestPayloadTagging;
  }

  public boolean isCloudRequestPayloadTaggingEnabled() {
    return cloudRequestPayloadTagging != null;
  }

  public List<String> getCloudResponsePayloadTagging() {
    return cloudResponsePayloadTagging == null
        ? Collections.emptyList()
        : cloudResponsePayloadTagging;
  }

  public boolean isCloudResponsePayloadTaggingEnabled() {
    return cloudResponsePayloadTagging != null;
  }

  public int getCloudPayloadTaggingMaxDepth() {
    return cloudPayloadTaggingMaxDepth;
  }

  public int getCloudPayloadTaggingMaxTags() {
    return cloudPayloadTaggingMaxTags;
  }

  public RumInjectorConfig getRumInjectorConfig() {
    return this.rumInjectorConfig;
  }

  public boolean isAiGuardEnabled() {
    return aiGuardEnabled;
  }

  public String getAiGuardEndpoint() {
    return aiGuardEndpoint;
  }

  public int getAiGuardMaxContentSize() {
    return aiGuardMaxContentSize;
  }

  public int getAiGuardMaxMessagesLength() {
    return aiGuardMaxMessagesLength;
  }

  public int getAiGuardTimeout() {
    return aiGuardTimeout;
  }

  private <T> Set<T> getSettingsSetFromEnvironment(
      String name, Function<String, T> mapper, boolean splitOnWS) {
    final String value = configProvider.getString(name, "");
    return convertStringSetToSet(
        name, parseStringIntoSetOfNonEmptyStrings(value, splitOnWS), mapper);
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

  public static final String PREFIX = "dd.";

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
    return parseStringIntoSetOfNonEmptyStrings(str, true);
  }

  @Nonnull
  private static Set<String> parseStringIntoSetOfNonEmptyStrings(
      final String str, boolean splitOnWS) {
    // Using LinkedHashSet to preserve original string order
    final Set<String> result = new LinkedHashSet<>();
    // Java returns single value when splitting an empty string. We do not need that value, so
    // we need to throw it out.
    int start = 0;
    int i = 0;
    for (; i < str.length(); ++i) {
      char c = str.charAt(i);
      if (c == ',' || (splitOnWS && Character.isWhitespace(c))) {
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
      possibleHostname = ConfigStrings.trim(possibleHostname);
      if (!possibleHostname.isEmpty()) {
        log.debug("Determined hostname from file {}", hostNameFile);
        return possibleHostname;
      }
    }

    // Try hostname command
    try (final TraceScope scope = AgentTracer.get().muteTracing();
        final BufferedReader reader =
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
    String value = ConfigHelper.env(name);
    if (value != null) {
      // Report non-default sequence id for consistency
      ConfigCollector.get().put(name, value, ConfigOrigin.ENV, NON_DEFAULT_SEQ_ID);
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
    String value = SystemProperties.getOrDefault(name, def);
    if (value != null) {
      // Report non-default sequence id for consistency
      ConfigCollector.get().put(name, value, ConfigOrigin.JVM_PROP, NON_DEFAULT_SEQ_ID);
    }
    return value;
  }

  // This has to be placed after all other static fields to give them a chance to initialize
  private static final Config INSTANCE =
      new Config(
          Platform.isNativeImageBuilder()
              ? ConfigProvider.withoutCollector()
              : ConfigProvider.getInstance(),
          InstrumenterConfig.get());

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
        + ", experimentalFeaturesEnabled="
        + experimentalFeaturesEnabled
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
        + ", httpClientPathResourceNameMapping="
        + httpClientPathResourceNameMapping
        + ", httpClientTagQueryString="
        + httpClientTagQueryString
        + ", httpClientSplitByDomain="
        + httpClientSplitByDomain
        + ", httpResourceRemoveTrailingSlash="
        + httpResourceRemoveTrailingSlash
        + ", dbClientSplitByInstance="
        + dbClientSplitByInstance
        + ", dbClientSplitByInstanceTypeSuffix="
        + dbClientSplitByInstanceTypeSuffix
        + ", dbClientSplitByHost="
        + dbClientSplitByHost
        + ", dbmInjectSqlBaseHash="
        + dbmInjectSqlBaseHash
        + ", dbmPropagationMode="
        + dbmPropagationMode
        + ", dbmTracePreparedStatements="
        + dbmTracePreparedStatements
        + ", splitByTags="
        + splitByTags
        + ", jeeSplitByDeployment="
        + jeeSplitByDeployment
        + ", scopeDepthLimit="
        + scopeDepthLimit
        + ", scopeStrictMode="
        + scopeStrictMode
        + ", scopeIterationKeepAlive="
        + scopeIterationKeepAlive
        + ", partialFlushMinSpans="
        + partialFlushMinSpans
        + ", traceKeepLatencyThresholdEnabled="
        + traceKeepLatencyThresholdEnabled
        + ", traceKeepLatencyThreshold="
        + traceKeepLatencyThreshold
        + ", traceStrictWritesEnabled="
        + traceStrictWritesEnabled
        + ", traceBaggageTagKeys="
        + traceBaggageTagKeys
        + ", tracePropagationStylesToExtract="
        + tracePropagationStylesToExtract
        + ", tracePropagationStylesToInject="
        + tracePropagationStylesToInject
        + ", tracePropagationBehaviorExtract="
        + tracePropagationBehaviorExtract
        + ", tracePropagationExtractFirst="
        + tracePropagationExtractFirst
        + ", traceInferredProxyEnabled="
        + traceInferredProxyEnabled
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
        + ", remoteConfigPollIntervalSeconds="
        + remoteConfigPollIntervalSeconds
        + ", remoteConfigMaxPayloadSize="
        + remoteConfigMaxPayloadSize
        + ", remoteConfigIntegrityCheckEnabled="
        + remoteConfigIntegrityCheckEnabled
        + ", debuggerEnabled="
        + dynamicInstrumentationEnabled
        + ", debuggerUploadTimeout="
        + dynamicInstrumentationUploadTimeout
        + ", debuggerUploadFlushInterval="
        + dynamicInstrumentationUploadFlushInterval
        + ", debuggerClassFileDumpEnabled="
        + dynamicInstrumentationClassFileDumpEnabled
        + ", debuggerPollInterval="
        + dynamicInstrumentationPollInterval
        + ", debuggerDiagnosticsInterval="
        + dynamicInstrumentationDiagnosticsInterval
        + ", debuggerMetricEnabled="
        + dynamicInstrumentationMetricEnabled
        + ", debuggerProbeFileLocation="
        + dynamicInstrumentationProbeFile
        + ", debuggerUploadBatchSize="
        + dynamicInstrumentationUploadBatchSize
        + ", debuggerMaxPayloadSize="
        + dynamicInstrumentationMaxPayloadSize
        + ", debuggerVerifyByteCode="
        + dynamicInstrumentationVerifyByteCode
        + ", debuggerInstrumentTheWorld="
        + dynamicInstrumentationInstrumentTheWorld
        + ", debuggerExcludeFiles="
        + dynamicInstrumentationExcludeFiles
        + ", debuggerIncludeFiles="
        + dynamicInstrumentationIncludeFiles
        + ", debuggerCaptureTimeout="
        + dynamicInstrumentationCaptureTimeout
        + ", debuggerRedactIdentifiers="
        + dynamicInstrumentationRedactedIdentifiers
        + ", debuggerRedactTypes="
        + dynamicInstrumentationRedactedTypes
        + ", debuggerSymbolEnabled="
        + symbolDatabaseEnabled
        + ", debuggerSymbolForceUpload="
        + symbolDatabaseForceUpload
        + ", debuggerSymbolFlushThreshold="
        + symbolDatabaseFlushThreshold
        + ", thirdPartyIncludes="
        + debuggerThirdPartyIncludes
        + ", thirdPartyExcludes="
        + debuggerThirdPartyExcludes
        + ", debuggerExceptionEnabled="
        + debuggerExceptionEnabled
        + ", debuggerCodeOriginEnabled="
        + debuggerCodeOriginEnabled
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
        + ", resilience4jMeasuredEnable="
        + resilience4jMeasuredEnabled
        + ", resilience4jTagMetricsEnabled="
        + resilience4jTagMetricsEnabled
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
        + ", logLevel="
        + logLevel
        + ", debugEnabled="
        + debugEnabled
        + ", triageEnabled="
        + triageEnabled
        + ", triageReportDir="
        + triageReportDir
        + ", startLogsEnabled="
        + startupLogsEnabled
        + ", configFile='"
        + configFileStatus
        + '\''
        + ", idGenerationStrategy="
        + idGenerationStrategy
        + ", trace128bitTraceIdGenerationEnabled="
        + trace128bitTraceIdGenerationEnabled
        + ", logs128bitTraceIdEnabled="
        + logs128bitTraceIdEnabled
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
        + ", apiSecurityEnabled="
        + apiSecurityEnabled
        + ", apiSecurityEndpointCollectionEnabled="
        + apiSecurityEndpointCollectionEnabled
        + ", apiSecurityEndpointCollectionMessageLimit="
        + apiSecurityEndpointCollectionMessageLimit
        + ", cwsEnabled="
        + cwsEnabled
        + ", cwsTlsRefresh="
        + cwsTlsRefresh
        + ", longRunningTraceEnabled="
        + longRunningTraceEnabled
        + ", longRunningTraceInitialFlushInterval="
        + longRunningTraceInitialFlushInterval
        + ", longRunningTraceFlushInterval="
        + longRunningTraceFlushInterval
        + ", cassandraKeyspaceStatementExtractionEnabled="
        + cassandraKeyspaceStatementExtractionEnabled
        + ", couchbaseInternalSpansEnabled="
        + couchbaseInternalSpansEnabled
        + ", elasticsearchBodyEnabled="
        + elasticsearchBodyEnabled
        + ", elasticsearchParamsEnabled="
        + elasticsearchParamsEnabled
        + ", elasticsearchBodyAndParamsEnabled="
        + elasticsearchBodyAndParamsEnabled
        + ", traceFlushInterval="
        + traceFlushIntervalSeconds
        + ", injectBaggageAsTagsEnabled="
        + injectBaggageAsTagsEnabled
        + ", logsInjectionEnabled="
        + logsInjectionEnabled
        + ", sparkTaskHistogramEnabled="
        + sparkTaskHistogramEnabled
        + ", sparkAppNameAsService="
        + sparkAppNameAsService
        + ", jaxRsExceptionAsErrorsEnabled="
        + jaxRsExceptionAsErrorsEnabled
        + ", axisPromoteResourceName="
        + axisPromoteResourceName
        + ", peerHostNameEnabled="
        + peerHostNameEnabled
        + ", peerServiceDefaultsEnabled="
        + peerServiceDefaultsEnabled
        + ", peerServiceComponentOverrides="
        + peerServiceComponentOverrides
        + ", removeIntegrationServiceNamesEnabled="
        + removeIntegrationServiceNamesEnabled
        + ", spanAttributeSchemaVersion="
        + spanAttributeSchemaVersion
        + ", telemetryDebugRequestsEnabled="
        + telemetryDebugRequestsEnabled
        + ", telemetryMetricsEnabled="
        + telemetryMetricsEnabled
        + ", appSecScaEnabled="
        + appSecScaEnabled
        + ", appSecRaspEnabled="
        + appSecRaspEnabled
        + ", dataJobsEnabled="
        + dataJobsEnabled
        + ", dataJobsOpenLineageEnabled="
        + dataJobsOpenLineageEnabled
        + ", dataJobsOpenLineageTimeoutEnabled="
        + dataJobsOpenLineageTimeoutEnabled
        + ", apmTracingEnabled="
        + apmTracingEnabled
        + ", jdkSocketEnabled="
        + jdkSocketEnabled
        + ", cloudPayloadTaggingServices="
        + cloudPayloadTaggingServices
        + ", cloudRequestPayloadTagging="
        + cloudRequestPayloadTagging
        + ", cloudResponsePayloadTagging="
        + cloudResponsePayloadTagging
        + ", experimentalPropagateProcessTagsEnabled="
        + experimentalPropagateProcessTagsEnabled
        + ", rumInjectorConfig="
        + (rumInjectorConfig == null ? "null" : rumInjectorConfig.jsonPayload())
        + ", aiGuardEnabled="
        + aiGuardEnabled
        + ", aiGuardEndpoint="
        + aiGuardEndpoint
        + ", serviceDiscoveryEnabled="
        + serviceDiscoveryEnabled
        + '}';
  }
}
