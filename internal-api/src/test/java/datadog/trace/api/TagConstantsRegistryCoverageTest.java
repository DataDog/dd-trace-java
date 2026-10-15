package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Every tag-name constant in {@link Tags}, {@link InstrumentationTags} and {@link DDTags} is in the
 * tag registry, so instrumentation can set it by id -- or is listed here.
 *
 * <p>{@link #NOT_YET_REGISTERED} is a ratchet: registering a tag makes this test fail until its
 * entry is removed, so the list only gets shorter, and a new constant must be registered or added
 * to a list. {@link #NOT_TAG_NAMES} holds constants that are tag values or name prefixes, not
 * names.
 */
class TagConstantsRegistryCoverageTest {
  static final List<Class<?>> TAG_CONSTANT_CLASSES =
      Arrays.asList(Tags.class, InstrumentationTags.class, DDTags.class);

  /** Constants that aren't tag names: values of a tag (span kinds), and name prefixes. */
  static final Set<String> NOT_TAG_NAMES =
      new HashSet<>(
          Arrays.asList(
              "DDTags.DD_CODE_ORIGIN_PREFIX",
              "DDTags.LANGUAGE_TAG_VALUE",
              "Tags.LLMOBS_AGENT_SPAN_KIND",
              "Tags.LLMOBS_EMBEDDING_SPAN_KIND",
              "Tags.LLMOBS_LLM_SPAN_KIND",
              "Tags.LLMOBS_RETRIEVAL_SPAN_KIND",
              "Tags.LLMOBS_TASK_SPAN_KIND",
              "Tags.LLMOBS_TOOL_SPAN_KIND",
              "Tags.LLMOBS_WORKFLOW_SPAN_KIND",
              "Tags.SPAN_KIND_BROKER",
              "Tags.SPAN_KIND_CLIENT",
              "Tags.SPAN_KIND_CONSUMER",
              "Tags.SPAN_KIND_INTERNAL",
              "Tags.SPAN_KIND_PRODUCER",
              "Tags.SPAN_KIND_SERVER",
              "Tags.SPAN_KIND_TEST",
              "Tags.SPAN_KIND_TEST_MODULE",
              "Tags.SPAN_KIND_TEST_SESSION",
              "Tags.SPAN_KIND_TEST_SUITE"));

  /** Tag names not registered yet. Remove an entry when its tag is registered. */
  static final Set<String> NOT_YET_REGISTERED =
      new HashSet<>(
          Arrays.asList(
              "DDTags.APM_ENABLED",
              "DDTags.CI_ENV_VARS",
              "DDTags.CI_ITR_TESTS_SKIPPED",
              "DDTags.CI_LIBRARY_CONFIGURATION_ERROR_FLAKY_TESTS",
              "DDTags.CI_LIBRARY_CONFIGURATION_ERROR_KNOWN_TESTS",
              "DDTags.CI_LIBRARY_CONFIGURATION_ERROR_SETTINGS",
              "DDTags.CI_LIBRARY_CONFIGURATION_ERROR_SKIPPABLE_TESTS",
              "DDTags.CI_LIBRARY_CONFIGURATION_ERROR_TEST_MANAGEMENT_TESTS",
              "DDTags.DB_STATEMENT",
              "DDTags.DD_CODE_ORIGIN_FRAME_FILE",
              "DDTags.DD_CODE_ORIGIN_FRAME_LINE",
              "DDTags.DD_CODE_ORIGIN_FRAME_METHOD",
              "DDTags.DD_CODE_ORIGIN_FRAME_SIGNATURE",
              "DDTags.DD_CODE_ORIGIN_FRAME_SNAPSHOT_ID",
              "DDTags.DD_CODE_ORIGIN_FRAME_TYPE",
              "DDTags.DD_CODE_ORIGIN_TYPE",
              "DDTags.DECISION_MAKER_INHERITED",
              "DDTags.DECISION_MAKER_RESOURCE",
              "DDTags.DECISION_MAKER_SERVICE",
              "DDTags.HOST_VCPU_COUNT",
              "DDTags.HTTP_FRAGMENT",
              "DDTags.LIBRARY_VERSION_TAG_KEY",
              "DDTags.PATHWAY_HASH",
              "DDTags.PID_TAG",
              "DDTags.PROCESS_TAGS",
              "DDTags.PROFILING_CONTEXT_ENGINE",
              "DDTags.RUNTIME_VERSION_TAG",
              "DDTags.SCHEMA_VERSION_TAG_KEY",
              "DDTags.SDK_OTLP_EXPORT",
              "DDTags.SPAN_EVENTS",
              "DDTags.SPAN_LINKS",
              "DDTags.TEST_HAS_FAILED_TEST_REPLAY",
              "DDTags.TEST_IS_USER_PROVIDED_SERVICE",
              "DDTags.THREAD_ID",
              "DDTags.THREAD_NAME",
              "DDTags.TRACE_START_TIME",
              "DDTags.USER_NAME",
              "InstrumentationTags.AMQP_COMMAND",
              "InstrumentationTags.AMQP_DELIVERY_MODE",
              "InstrumentationTags.AMQP_EXCHANGE",
              "InstrumentationTags.AMQP_QUEUE",
              "InstrumentationTags.AMQP_ROUTING_KEY",
              "InstrumentationTags.AWS_AGENT",
              "InstrumentationTags.AWS_BUCKET_NAME",
              "InstrumentationTags.AWS_ENDPOINT",
              "InstrumentationTags.AWS_EXECUTION_ARN",
              "InstrumentationTags.AWS_FUNCTION_ARN",
              "InstrumentationTags.AWS_FUNCTION_NAME",
              "InstrumentationTags.AWS_OBJECT_KEY",
              "InstrumentationTags.AWS_OPERATION",
              "InstrumentationTags.AWS_QUEUE_NAME",
              "InstrumentationTags.AWS_QUEUE_URL",
              "InstrumentationTags.AWS_REQUEST_ID",
              "InstrumentationTags.AWS_SERVICE",
              "InstrumentationTags.AWS_STATE_MACHINE_ARN",
              "InstrumentationTags.AWS_STORAGE_CLASS",
              "InstrumentationTags.AWS_STREAM_ARN",
              "InstrumentationTags.AWS_STREAM_NAME",
              "InstrumentationTags.AWS_TABLE_NAME",
              "InstrumentationTags.AWS_TOPIC_ARN",
              "InstrumentationTags.AWS_TOPIC_NAME",
              "InstrumentationTags.BUCKET",
              "InstrumentationTags.BUCKET_NAME",
              "InstrumentationTags.CASSANDRA_CONTACT_POINTS",
              "InstrumentationTags.CONSUMER_GROUP",
              "InstrumentationTags.COUCHBASE_OPERATION_ID",
              "InstrumentationTags.COUCHBASE_SEED_NODES",
              "InstrumentationTags.DBM_TRACE_INJECTED",
              "InstrumentationTags.DB_COMMAND_CANCELLED",
              "InstrumentationTags.DB_COMMAND_RESULTS_COUNT",
              "InstrumentationTags.DB_REDIS_DBINDEX",
              "InstrumentationTags.DYNAMO_PRIMARY_KEY_1",
              "InstrumentationTags.DYNAMO_PRIMARY_KEY_1_VALUE",
              "InstrumentationTags.DYNAMO_PRIMARY_KEY_2",
              "InstrumentationTags.DYNAMO_PRIMARY_KEY_2_VALUE",
              "InstrumentationTags.ELASTICSEARCH_ID",
              "InstrumentationTags.ELASTICSEARCH_NODE_CLUSTER_NAME",
              "InstrumentationTags.ELASTICSEARCH_NODE_FAILURES",
              "InstrumentationTags.ELASTICSEARCH_REQUEST_INDICES",
              "InstrumentationTags.ELASTICSEARCH_REQUEST_SEARCH_TYPES",
              "InstrumentationTags.ELASTICSEARCH_REQUEST_WRITE_ROUTING",
              "InstrumentationTags.ELASTICSEARCH_REQUEST_WRITE_TYPE",
              "InstrumentationTags.ELASTICSEARCH_REQUEST_WRITE_VERSION",
              "InstrumentationTags.ELASTICSEARCH_RESPONSE_STATUS",
              "InstrumentationTags.ELASTICSEARCH_SHARD_BROADCAST_FAILED",
              "InstrumentationTags.ELASTICSEARCH_SHARD_BROADCAST_SUCCESSFUL",
              "InstrumentationTags.ELASTICSEARCH_SHARD_BROADCAST_TOTAL",
              "InstrumentationTags.ELASTICSEARCH_SHARD_BULK_ID",
              "InstrumentationTags.ELASTICSEARCH_SHARD_BULK_INDEX",
              "InstrumentationTags.ELASTICSEARCH_SHARD_REPLICATION_FAILED",
              "InstrumentationTags.ELASTICSEARCH_SHARD_REPLICATION_TOTAL",
              "InstrumentationTags.ELASTICSEARCH_TYPE",
              "InstrumentationTags.ELASTICSEARCH_VERSION",
              "InstrumentationTags.EVENT",
              "InstrumentationTags.GRPC_STATUS_CODE",
              "InstrumentationTags.HANDLER_TYPE",
              "InstrumentationTags.HYSTRIX_CIRCUIT_OPEN",
              "InstrumentationTags.HYSTRIX_COMMAND",
              "InstrumentationTags.HYSTRIX_GROUP",
              "InstrumentationTags.INSTRUMENTATION_TIME_MS",
              "InstrumentationTags.JMS_PRODUCE",
              "InstrumentationTags.JSP_CLASSFQCN",
              "InstrumentationTags.JSP_COMPILER",
              "InstrumentationTags.JSP_FORWARD_ORIGIN",
              "InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS",
              "InstrumentationTags.KAFKA_CLUSTER_ID",
              "InstrumentationTags.KAFKA_RECORDS_COUNT",
              "InstrumentationTags.MESSAGE",
              "InstrumentationTags.MESSAGE_SIZE",
              "InstrumentationTags.MESSAGE_TYPE",
              "InstrumentationTags.MESSAGING_DESTINATION_NAME",
              "InstrumentationTags.MULE_CORRELATION_ID",
              "InstrumentationTags.MULE_LOCATION",
              "InstrumentationTags.OFFSET",
              "InstrumentationTags.PARTITION",
              "InstrumentationTags.PROCESSOR_NAME",
              "InstrumentationTags.QUARTZ_JOB_GROUP",
              "InstrumentationTags.QUARTZ_JOB_NAME",
              "InstrumentationTags.QUARTZ_TRIGGER_GROUP",
              "InstrumentationTags.QUARTZ_TRIGGER_NAME",
              "InstrumentationTags.QUEUE_NAME",
              "InstrumentationTags.RECORD_END_TO_END_DURATION_MS",
              "InstrumentationTags.RECORD_QUEUE_TIME_MS",
              "InstrumentationTags.REQUEST_PREDICATE",
              "InstrumentationTags.S3_ETAG",
              "InstrumentationTags.SERVLET_DISPATCH",
              "InstrumentationTags.STATUS_CODE",
              "InstrumentationTags.STATUS_DESCRIPTION",
              "InstrumentationTags.STREAM_NAME",
              "InstrumentationTags.TABLE_NAME",
              "InstrumentationTags.TIBCO_NODE",
              "InstrumentationTags.TIBCO_VERSION",
              "InstrumentationTags.TIMEOUT",
              "InstrumentationTags.TOMBSTONE",
              "InstrumentationTags.TOPIC_NAME",
              "InstrumentationTags.TOP_LEVEL_AWS_SERVICE",
              "InstrumentationTags.TWILIO_ACCOUNT",
              "InstrumentationTags.TWILIO_PARENT_SID",
              "InstrumentationTags.TWILIO_SID",
              "InstrumentationTags.TWILIO_STATUS",
              "InstrumentationTags.TWILIO_TYPE",
              "InstrumentationTags.VIEW_TYPE",
              "InstrumentationTags.WEBSOCKET_CLOSE_CODE",
              "InstrumentationTags.WEBSOCKET_CLOSE_REASON",
              "InstrumentationTags.WEBSOCKET_MESSAGE_FRAMES",
              "InstrumentationTags.WEBSOCKET_MESSAGE_LENGTH",
              "InstrumentationTags.WEBSOCKET_MESSAGE_RECEIVE_TIME",
              "InstrumentationTags.WEBSOCKET_MESSAGE_TYPE",
              "InstrumentationTags.WEBSOCKET_SESSION_ID",
              "Tags.AI_GUARD_EVENT",
              "Tags.BASE_HASH",
              "Tags.CI_JOB_ID",
              "Tags.CI_JOB_NAME",
              "Tags.CI_JOB_URL",
              "Tags.CI_NODE_LABELS",
              "Tags.CI_NODE_NAME",
              "Tags.CI_PIPELINE_DISPLAY_NAME",
              "Tags.CI_PIPELINE_ID",
              "Tags.CI_PIPELINE_NAME",
              "Tags.CI_PIPELINE_NUMBER",
              "Tags.CI_PIPELINE_URL",
              "Tags.CI_PROVIDER_NAME",
              "Tags.CI_STAGE_NAME",
              "Tags.CI_WORKSPACE_PATH",
              "Tags.DB_HOST",
              "Tags.DB_SCHEMA",
              "Tags.DB_WAREHOUSE",
              "Tags.DD_ENV",
              "Tags.DD_SERVICE",
              "Tags.DD_VERSION",
              "Tags.DSM_TRANSACTION_CHECKPOINT",
              "Tags.DSM_TRANSACTION_ID",
              "Tags.ERROR_DEBUG_INFO_CAPTURED",
              "Tags.GIT_BRANCH",
              "Tags.GIT_COMMIT_AUTHOR_DATE",
              "Tags.GIT_COMMIT_AUTHOR_EMAIL",
              "Tags.GIT_COMMIT_AUTHOR_NAME",
              "Tags.GIT_COMMIT_COMMITTER_DATE",
              "Tags.GIT_COMMIT_COMMITTER_EMAIL",
              "Tags.GIT_COMMIT_COMMITTER_NAME",
              "Tags.GIT_COMMIT_HEAD_AUTHOR_DATE",
              "Tags.GIT_COMMIT_HEAD_AUTHOR_EMAIL",
              "Tags.GIT_COMMIT_HEAD_AUTHOR_NAME",
              "Tags.GIT_COMMIT_HEAD_COMMITTER_DATE",
              "Tags.GIT_COMMIT_HEAD_COMMITTER_EMAIL",
              "Tags.GIT_COMMIT_HEAD_COMMITTER_NAME",
              "Tags.GIT_COMMIT_HEAD_MESSAGE",
              "Tags.GIT_COMMIT_HEAD_SHA",
              "Tags.GIT_COMMIT_MESSAGE",
              "Tags.GIT_COMMIT_SHA",
              "Tags.GIT_PULL_REQUEST_BASE_BRANCH",
              "Tags.GIT_PULL_REQUEST_BASE_BRANCH_HEAD_SHA",
              "Tags.GIT_PULL_REQUEST_BASE_BRANCH_SHA",
              "Tags.GIT_REPOSITORY_URL",
              "Tags.GIT_TAG",
              "Tags.HTTP_ENDPOINT",
              "Tags.HTTP_FORWARDED",
              "Tags.HTTP_FORWARDED_HOST",
              "Tags.HTTP_FORWARDED_IP",
              "Tags.HTTP_FORWARDED_PORT",
              "Tags.HTTP_FORWARDED_PROTO",
              "Tags.HTTP_REQUEST_CONTENT_LENGTH",
              "Tags.HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN",
              "Tags.HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST",
              "Tags.HTTP_RESPONSE_CONTENT_LENGTH",
              "Tags.ITR_CORRELATION_ID",
              "Tags.MESSAGE_BUS_DESTINATION",
              "Tags.OS_ARCHITECTURE",
              "Tags.OS_PLATFORM",
              "Tags.OS_VERSION",
              "Tags.PULL_REQUEST_NUMBER",
              "Tags.RPC_SERVICE",
              "Tags.RUNTIME_NAME",
              "Tags.RUNTIME_VENDOR",
              "Tags.RUNTIME_VERSION",
              "Tags.TEST_ANDROID_API_LEVEL",
              "Tags.TEST_ANDROID_CODENAME",
              "Tags.TEST_ANDROID_RELEASE",
              "Tags.TEST_ANDROID_ROBOLECTRIC_VERSION",
              "Tags.TEST_BROWSER_DRIVER",
              "Tags.TEST_BROWSER_DRIVER_VERSION",
              "Tags.TEST_BROWSER_NAME",
              "Tags.TEST_BROWSER_VERSION",
              "Tags.TEST_CALLBACK",
              "Tags.TEST_CODEOWNERS",
              "Tags.TEST_CODE_COVERAGE_BACKFILLED",
              "Tags.TEST_CODE_COVERAGE_ENABLED",
              "Tags.TEST_CODE_COVERAGE_LINES_PERCENTAGE",
              "Tags.TEST_COMMAND",
              "Tags.TEST_EARLY_FLAKE_ABORT_REASON",
              "Tags.TEST_EARLY_FLAKE_ENABLED",
              "Tags.TEST_EXECUTION",
              "Tags.TEST_FAILURE_SUPPRESSED",
              "Tags.TEST_FINAL_STATUS",
              "Tags.TEST_FRAMEWORK_VERSION",
              "Tags.TEST_GRADLE_NESTED_BUILD",
              "Tags.TEST_HAS_FAILED_ALL_RETRIES",
              "Tags.TEST_IS_ANDROID",
              "Tags.TEST_IS_MODIFIED",
              "Tags.TEST_IS_NEW",
              "Tags.TEST_IS_RETRY",
              "Tags.TEST_IS_RUM_ACTIVE",
              "Tags.TEST_ITR_FORCED_RUN",
              "Tags.TEST_ITR_TESTS_SKIPPING_COUNT",
              "Tags.TEST_ITR_TESTS_SKIPPING_ENABLED",
              "Tags.TEST_ITR_TESTS_SKIPPING_TYPE",
              "Tags.TEST_ITR_UNSKIPPABLE",
              "Tags.TEST_JUNIT_IS_DYNAMIC",
              "Tags.TEST_MODULE",
              "Tags.TEST_MODULE_ID",
              "Tags.TEST_PARAMETERS",
              "Tags.TEST_RETRY_REASON",
              "Tags.TEST_SESSION_ID",
              "Tags.TEST_SESSION_NAME",
              "Tags.TEST_SKIPPED_BY_ITR",
              "Tags.TEST_SKIP_REASON",
              "Tags.TEST_SOURCE_CLASS",
              "Tags.TEST_SOURCE_END",
              "Tags.TEST_SOURCE_FILE",
              "Tags.TEST_SOURCE_METHOD",
              "Tags.TEST_SOURCE_START",
              "Tags.TEST_SUITE_ID",
              "Tags.TEST_TEST_MANAGEMENT_ATTEMPT_TO_FIX_PASSED",
              "Tags.TEST_TEST_MANAGEMENT_ENABLED",
              "Tags.TEST_TEST_MANAGEMENT_IS_ATTEMPT_TO_FIX",
              "Tags.TEST_TEST_MANAGEMENT_IS_QUARANTINED",
              "Tags.TEST_TEST_MANAGEMENT_IS_TEST_DISABLED",
              "Tags.TEST_TOOLCHAIN",
              "Tags.TEST_TRAITS",
              "Tags.TEST_TYPE"));

  @Test
  void everyTagConstantIsRegisteredOrListed() {
    List<String> unlisted = new ArrayList<>();
    for (Map.Entry<String, String> constant : tagConstants().entrySet()) {
      String name = constant.getKey();
      if (!isRegistered(constant.getValue())
          && !NOT_YET_REGISTERED.contains(name)
          && !NOT_TAG_NAMES.contains(name)) {
        unlisted.add(name + " = \"" + constant.getValue() + "\"");
      }
    }
    assertEquals(
        Collections.emptyList(),
        unlisted,
        "register these tags in tag-conventions.yaml (or the Java overlay), or list them here");
  }

  @Test
  void noConstantListedAsNotYetRegisteredIsRegistered() {
    Map<String, String> constants = tagConstants();
    List<String> registered = new ArrayList<>();
    for (String name : NOT_YET_REGISTERED) {
      String value = constants.get(name);
      if (value != null && isRegistered(value)) {
        registered.add(name);
      }
    }
    Collections.sort(registered);
    assertEquals(
        Collections.emptyList(), registered, "now registered: remove from NOT_YET_REGISTERED");
  }

  @Test
  void everyListedConstantExists() {
    Set<String> names = tagConstants().keySet();
    for (String listed : NOT_YET_REGISTERED) {
      assertTrue(names.contains(listed), "no such constant: " + listed);
    }
    for (String listed : NOT_TAG_NAMES) {
      assertTrue(names.contains(listed), "no such constant: " + listed);
    }
  }

  private static boolean isRegistered(String tag) {
    return KnownTagCodec.keyOf(tag) != 0;
  }

  /** Every public static final String constant of the tag classes, keyed {@code Class.FIELD}. */
  private static Map<String, String> tagConstants() {
    Map<String, String> constants = new TreeMap<>();
    for (Class<?> cls : TAG_CONSTANT_CLASSES) {
      for (Field field : cls.getDeclaredFields()) {
        int mods = field.getModifiers();
        if (field.getType() == String.class
            && Modifier.isPublic(mods)
            && Modifier.isStatic(mods)
            && Modifier.isFinal(mods)) {
          try {
            constants.put(cls.getSimpleName() + "." + field.getName(), (String) field.get(null));
          } catch (IllegalAccessException e) {
            throw new AssertionError(e);
          }
        }
      }
    }
    return constants;
  }
}
