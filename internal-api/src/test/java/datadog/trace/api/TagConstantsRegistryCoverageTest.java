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
 * to a list. {@link #NEVER_REGISTERED} holds constants that are deliberately never registered, each
 * with its reason.
 */
class TagConstantsRegistryCoverageTest {
  static final List<Class<?>> TAG_CONSTANT_CLASSES =
      Arrays.asList(Tags.class, InstrumentationTags.class, DDTags.class);

  /**
   * Constants deliberately never registered, with the reason: values of a tag, name prefixes and
   * patterns, operation names, and names only the serializers write.
   */
  static final Map<String, String> NEVER_REGISTERED = new TreeMap<>();

  static {
    NEVER_REGISTERED.put("DDTags.DB_STATEMENT", "unused, but public API");
    NEVER_REGISTERED.put(
        "DDTags.DD_CODE_ORIGIN_FRAME_FILE",
        "an instance of a name pattern (_dd.code_origin.frames.N.*), not modeled yet");
    NEVER_REGISTERED.put(
        "DDTags.DD_CODE_ORIGIN_FRAME_LINE",
        "an instance of a name pattern (_dd.code_origin.frames.N.*), not modeled yet");
    NEVER_REGISTERED.put(
        "DDTags.DD_CODE_ORIGIN_FRAME_METHOD",
        "an instance of a name pattern (_dd.code_origin.frames.N.*), not modeled yet");
    NEVER_REGISTERED.put(
        "DDTags.DD_CODE_ORIGIN_FRAME_SIGNATURE",
        "an instance of a name pattern (_dd.code_origin.frames.N.*), not modeled yet");
    NEVER_REGISTERED.put(
        "DDTags.DD_CODE_ORIGIN_FRAME_SNAPSHOT_ID",
        "an instance of a name pattern (_dd.code_origin.frames.N.*), not modeled yet");
    NEVER_REGISTERED.put(
        "DDTags.DD_CODE_ORIGIN_FRAME_TYPE",
        "an instance of a name pattern (_dd.code_origin.frames.N.*), not modeled yet");
    NEVER_REGISTERED.put("DDTags.DD_CODE_ORIGIN_PREFIX", "a name prefix");
    NEVER_REGISTERED.put("DDTags.LANGUAGE_TAG_VALUE", "a value of language");
    NEVER_REGISTERED.put(
        "DDTags.PROCESS_TAGS", "written by the serializers, never stored as a span tag");
    NEVER_REGISTERED.put(
        "DDTags.SDK_OTLP_EXPORT", "written by the serializers, never stored as a span tag");
    NEVER_REGISTERED.put("InstrumentationTags.SERVLET_DISPATCH", "an operation name, not a tag");
    NEVER_REGISTERED.put(
        "Tags.LLMOBS_AGENT_SPAN_KIND", "a value of an LLM Observability span kind");
    NEVER_REGISTERED.put(
        "Tags.LLMOBS_EMBEDDING_SPAN_KIND", "a value of an LLM Observability span kind");
    NEVER_REGISTERED.put("Tags.LLMOBS_LLM_SPAN_KIND", "a value of an LLM Observability span kind");
    NEVER_REGISTERED.put(
        "Tags.LLMOBS_RETRIEVAL_SPAN_KIND", "a value of an LLM Observability span kind");
    NEVER_REGISTERED.put("Tags.LLMOBS_TASK_SPAN_KIND", "a value of an LLM Observability span kind");
    NEVER_REGISTERED.put("Tags.LLMOBS_TOOL_SPAN_KIND", "a value of an LLM Observability span kind");
    NEVER_REGISTERED.put(
        "Tags.LLMOBS_WORKFLOW_SPAN_KIND", "a value of an LLM Observability span kind");
    NEVER_REGISTERED.put(
        "Tags.MESSAGE_BUS_DESTINATION",
        "unused in production; TagInterceptorTest splits by it as a custom tag");
    NEVER_REGISTERED.put("Tags.SPAN_KIND_BROKER", "a value of span.kind");
    NEVER_REGISTERED.put("Tags.SPAN_KIND_CLIENT", "a value of span.kind");
    NEVER_REGISTERED.put("Tags.SPAN_KIND_CONSUMER", "a value of span.kind");
    NEVER_REGISTERED.put("Tags.SPAN_KIND_INTERNAL", "a value of span.kind");
    NEVER_REGISTERED.put("Tags.SPAN_KIND_PRODUCER", "a value of span.kind");
    NEVER_REGISTERED.put("Tags.SPAN_KIND_SERVER", "a value of span.kind");
    NEVER_REGISTERED.put("Tags.SPAN_KIND_TEST", "a value of span.kind");
    NEVER_REGISTERED.put("Tags.SPAN_KIND_TEST_MODULE", "a value of span.kind");
    NEVER_REGISTERED.put("Tags.SPAN_KIND_TEST_SESSION", "a value of span.kind");
    NEVER_REGISTERED.put("Tags.SPAN_KIND_TEST_SUITE", "a value of span.kind");
  }

  /** Tag names not registered yet. Remove an entry when its tag is registered. */
  static final Set<String> NOT_YET_REGISTERED =
      new HashSet<>(
          Arrays.asList(
              "DDTags.CI_ENV_VARS",
              "DDTags.CI_ITR_TESTS_SKIPPED",
              "DDTags.CI_LIBRARY_CONFIGURATION_ERROR_FLAKY_TESTS",
              "DDTags.CI_LIBRARY_CONFIGURATION_ERROR_KNOWN_TESTS",
              "DDTags.CI_LIBRARY_CONFIGURATION_ERROR_SETTINGS",
              "DDTags.CI_LIBRARY_CONFIGURATION_ERROR_SKIPPABLE_TESTS",
              "DDTags.CI_LIBRARY_CONFIGURATION_ERROR_TEST_MANAGEMENT_TESTS",
              "DDTags.HOST_VCPU_COUNT",
              "DDTags.LIBRARY_VERSION_TAG_KEY",
              "DDTags.PID_TAG",
              "DDTags.RUNTIME_VERSION_TAG",
              "DDTags.SPAN_EVENTS",
              "DDTags.TEST_HAS_FAILED_TEST_REPLAY",
              "DDTags.TEST_IS_USER_PROVIDED_SERVICE",
              "DDTags.THREAD_ID",
              "DDTags.THREAD_NAME",
              "DDTags.TRACE_START_TIME",
              "DDTags.USER_NAME",
              "InstrumentationTags.COUCHBASE_OPERATION_ID",
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
              "InstrumentationTags.HYSTRIX_CIRCUIT_OPEN",
              "InstrumentationTags.HYSTRIX_COMMAND",
              "InstrumentationTags.HYSTRIX_GROUP",
              "InstrumentationTags.JSP_CLASSFQCN",
              "InstrumentationTags.JSP_COMPILER",
              "InstrumentationTags.JSP_FORWARD_ORIGIN",
              "InstrumentationTags.QUARTZ_JOB_GROUP",
              "InstrumentationTags.QUARTZ_JOB_NAME",
              "InstrumentationTags.QUARTZ_TRIGGER_GROUP",
              "InstrumentationTags.QUARTZ_TRIGGER_NAME",
              "InstrumentationTags.STATUS_CODE",
              "InstrumentationTags.STATUS_DESCRIPTION",
              "InstrumentationTags.TIMEOUT",
              "InstrumentationTags.TWILIO_ACCOUNT",
              "InstrumentationTags.TWILIO_PARENT_SID",
              "InstrumentationTags.TWILIO_SID",
              "InstrumentationTags.TWILIO_STATUS",
              "InstrumentationTags.TWILIO_TYPE",
              "Tags.AI_GUARD_EVENT",
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
              "Tags.DD_ENV",
              "Tags.DD_SERVICE",
              "Tags.DD_VERSION",
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
              "Tags.ITR_CORRELATION_ID",
              "Tags.OS_ARCHITECTURE",
              "Tags.OS_PLATFORM",
              "Tags.OS_VERSION",
              "Tags.PULL_REQUEST_NUMBER",
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
          && !NEVER_REGISTERED.containsKey(name)) {
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
    for (String listed : NEVER_REGISTERED.keySet()) {
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
