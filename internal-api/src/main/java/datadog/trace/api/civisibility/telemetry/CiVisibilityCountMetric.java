package datadog.trace.api.civisibility.telemetry;

import datadog.trace.api.civisibility.telemetry.tag.AgentlessLogSubmissionEnabled;
import datadog.trace.api.civisibility.telemetry.tag.AutoInjected;
import datadog.trace.api.civisibility.telemetry.tag.BrowserDriver;
import datadog.trace.api.civisibility.telemetry.tag.Command;
import datadog.trace.api.civisibility.telemetry.tag.CoverageEnabled;
import datadog.trace.api.civisibility.telemetry.tag.CoverageErrorType;
import datadog.trace.api.civisibility.telemetry.tag.EarlyFlakeDetectionAbortReason;
import datadog.trace.api.civisibility.telemetry.tag.EarlyFlakeDetectionEnabled;
import datadog.trace.api.civisibility.telemetry.tag.Endpoint;
import datadog.trace.api.civisibility.telemetry.tag.ErrorType;
import datadog.trace.api.civisibility.telemetry.tag.EventType;
import datadog.trace.api.civisibility.telemetry.tag.ExitCode;
import datadog.trace.api.civisibility.telemetry.tag.FailFastTestOrderEnabled;
import datadog.trace.api.civisibility.telemetry.tag.FailedTestReplayEnabled;
import datadog.trace.api.civisibility.telemetry.tag.FlakyTestRetriesEnabled;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderDiscrepant;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderExpected;
import datadog.trace.api.civisibility.telemetry.tag.GitShaDiscrepancyType;
import datadog.trace.api.civisibility.telemetry.tag.GitShaMatch;
import datadog.trace.api.civisibility.telemetry.tag.HasCodeowner;
import datadog.trace.api.civisibility.telemetry.tag.HasFailedAllRetries;
import datadog.trace.api.civisibility.telemetry.tag.ImpactedTestsDetectionEnabled;
import datadog.trace.api.civisibility.telemetry.tag.IsAttemptToFix;
import datadog.trace.api.civisibility.telemetry.tag.IsDisabled;
import datadog.trace.api.civisibility.telemetry.tag.IsHeadless;
import datadog.trace.api.civisibility.telemetry.tag.IsModified;
import datadog.trace.api.civisibility.telemetry.tag.IsNew;
import datadog.trace.api.civisibility.telemetry.tag.IsQuarantined;
import datadog.trace.api.civisibility.telemetry.tag.IsRetry;
import datadog.trace.api.civisibility.telemetry.tag.IsRum;
import datadog.trace.api.civisibility.telemetry.tag.IsUnsupportedCI;
import datadog.trace.api.civisibility.telemetry.tag.ItrEnabled;
import datadog.trace.api.civisibility.telemetry.tag.ItrSkipEnabled;
import datadog.trace.api.civisibility.telemetry.tag.KnownTestsEnabled;
import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.civisibility.telemetry.tag.RequestCompressed;
import datadog.trace.api.civisibility.telemetry.tag.RequireGit;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.api.civisibility.telemetry.tag.StatusCode;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.api.civisibility.telemetry.tag.TestManagementEnabled;
import java.util.Arrays;

public enum CiVisibilityCountMetric {

  /**
   * The number of test sessions started. This metric is separate from event_created to avoid
   * increasing the cardinality too much
   */
  TEST_SESSION(
      "test_session",
      Provider.class,
      AutoInjected.class,
      AgentlessLogSubmissionEnabled.class,
      FailFastTestOrderEnabled.class),
  /** The number of events created */
  EVENT_CREATED(
      "event_created",
      TestFrameworkInstrumentation.class,
      EventType.class,
      IsHeadless.class,
      HasCodeowner.class,
      IsUnsupportedCI.class),
  /** The number of events finished */
  EVENT_FINISHED(
      "event_finished",
      TestFrameworkInstrumentation.class,
      EventType.class,
      IsHeadless.class,
      HasCodeowner.class,
      IsUnsupportedCI.class,
      EarlyFlakeDetectionAbortReason.class,
      FailedTestReplayEnabled.SessionMetric.class),
  /** The number of test events finished */
  TEST_EVENT_FINISHED(
      "event_finished",
      TestFrameworkInstrumentation.class,
      EventType.class,
      IsNew.class,
      IsModified.class,
      IsQuarantined.class,
      IsDisabled.class,
      IsAttemptToFix.class,
      IsRetry.class,
      HasFailedAllRetries.class,
      RetryReason.class,
      FailedTestReplayEnabled.TestMetric.class,
      IsRum.class,
      BrowserDriver.class),
  /** The number of successfully collected code coverages that are empty */
  CODE_COVERAGE_IS_EMPTY("code_coverage.is_empty"),
  /** The number of errors while processing code coverage */
  CODE_COVERAGE_ERRORS("code_coverage.errors", CoverageErrorType.class),
  /** The number of events created using the manual API */
  MANUAL_API_EVENTS("manual_api_events", EventType.class),
  /** The number of events enqueued for serialization */
  EVENTS_ENQUEUED_FOR_SERIALIZATION("events_enqueued_for_serialization"),
  /** The number of requests sent to the endpoint, regardless of success */
  ENDPOINT_PAYLOAD_REQUESTS("endpoint_payload.requests", Endpoint.class, RequestCompressed.class),
  /** The number of requests sent to the endpoint that errored */
  ENDPOINT_PAYLOAD_REQUESTS_ERRORS(
      "endpoint_payload.requests_errors", Endpoint.class, ErrorType.class, StatusCode.class),
  /** The number of payloads dropped after all retries */
  ENDPOINT_PAYLOAD_DROPPED("endpoint_payload.dropped", Endpoint.class),
  /** The number of git commands executed */
  GIT_COMMAND("git.command", Command.class),
  /** The number of git commands that errored */
  GIT_COMMAND_ERRORS("git.command_errors", Command.class, ExitCode.class),
  /** Number of commit sha comparisons and if they matched when building git info for a repo */
  GIT_COMMIT_SHA_MATCH("git.commit_sha_match", GitShaMatch.class),
  /** Number of sha mismatches when building git info for a repo */
  GIT_COMMIT_SHA_DISCREPANCY(
      "git.commit_sha_discrepancy",
      GitProviderExpected.class,
      GitProviderDiscrepant.class,
      GitShaDiscrepancyType.class),
  /** The number of requests sent to the search commit endpoint */
  GIT_REQUESTS_SEARCH_COMMITS("git_requests.search_commits", RequestCompressed.class),
  /** The number of search commit requests sent to the endpoint that errored */
  GIT_REQUESTS_SEARCH_COMMITS_ERRORS(
      "git_requests.search_commits_errors", ErrorType.class, StatusCode.class),
  /** The number of requests sent to the git object pack endpoint */
  GIT_REQUESTS_OBJECTS_PACK("git_requests.objects_pack", RequestCompressed.class),
  /** The number of git object pack requests sent to the endpoint that errored */
  GIT_REQUESTS_OBJECTS_PACK_ERRORS(
      "git_requests.objects_pack_errors", ErrorType.class, StatusCode.class),
  /** The number of requests sent to the settings endpoint, regardless of success */
  GIT_REQUESTS_SETTINGS("git_requests.settings", RequestCompressed.class),
  /** The number of settings requests sent to the endpoint that errored */
  GIT_REQUESTS_SETTINGS_ERRORS("git_requests.settings_errors", ErrorType.class, StatusCode.class),
  /** The number of settings responses from the endpoint */
  GIT_REQUESTS_SETTINGS_RESPONSE(
      "git_requests.settings_response",
      ItrEnabled.class,
      ItrSkipEnabled.class,
      CoverageEnabled.class,
      EarlyFlakeDetectionEnabled.class,
      FlakyTestRetriesEnabled.class,
      ImpactedTestsDetectionEnabled.class,
      KnownTestsEnabled.class,
      TestManagementEnabled.class,
      FailedTestReplayEnabled.SettingsMetric.class,
      RequireGit.class),
  /** The number of requests sent to the itr skippable tests endpoint */
  ITR_SKIPPABLE_TESTS_REQUEST("itr_skippable_tests.request", RequestCompressed.class),
  /** The number of itr skippable tests requests sent to the endpoint that errored */
  ITR_SKIPPABLE_TESTS_REQUEST_ERRORS(
      "itr_skippable_tests.request_errors", ErrorType.class, StatusCode.class),
  /** The number of tests to skip returned by the endpoint */
  ITR_SKIPPABLE_TESTS_RESPONSE_TESTS("itr_skippable_tests.response_tests"),
  /** The number of tests or test suites skipped */
  ITR_SKIPPED("itr_skipped", EventType.class),
  /** The number of tests or test suites that are seen as unskippable */
  ITR_UNSKIPPABLE("itr_unskippable", EventType.class),
  /**
   * The number of tests or test suites that would've been skipped by ITR but were forced to run
   * because of their unskippable status
   */
  ITR_FORCED_RUN("itr_forced_run", EventType.class),
  /** The number of requests sent to the known tests endpoint */
  KNOWN_TESTS_REQUEST("known_tests.request", RequestCompressed.class),
  /** The number of known tests requests sent to the known tests endpoint that errored */
  KNOWN_TESTS_REQUEST_ERRORS("known_tests.request_errors", ErrorType.class, StatusCode.class),
  /** The number of requests sent to the flaky tests endpoint */
  FLAKY_TESTS_REQUEST("flaky_tests.request", RequestCompressed.class),
  /** The number of tests requests sent to the flaky tests endpoint that errored */
  FLAKY_TESTS_REQUEST_ERRORS("flaky_tests.request_errors", ErrorType.class, StatusCode.class),
  /** The number of requests sent to the test management tests endpoint */
  TEST_MANAGEMENT_TESTS_REQUEST("test_management.request", RequestCompressed.class),
  /** The number of tests requests sent to the test management tests endpoint that errored */
  TEST_MANAGEMENT_TESTS_REQUEST_ERRORS(
      "test_management.request_errors", ErrorType.class, StatusCode.class),
  /** The number of coverage upload requests sent */
  COVERAGE_UPLOAD_REQUEST("coverage_upload.request", RequestCompressed.class),
  /** The number of coverage upload requests that errored */
  COVERAGE_UPLOAD_REQUEST_ERRORS(
      "coverage_upload.request_errors", ErrorType.class, StatusCode.class);

  // need a "holder" class, as accessing static fields from enum constructors is illegal
  static class IndexHolder {
    private static int INDEX = 0;
  }

  private final String name;
  private final int index;
  private final Class<? extends TagValue>[] tags;
  private final TagValue[][] tagValues;
  private final int[] tagIdxMultipliers;

  @SafeVarargs
  CiVisibilityCountMetric(String metricName, Class<? extends TagValue>... metricTags) {
    name = metricName;
    index = IndexHolder.INDEX;
    tags = metricTags;

    tagValues = new TagValue[tags.length][];
    for (int i = 0; i < tags.length; i++) {
      // getEnumConstants() creates a new copy of the array each time it is called, so "caching" the
      // results in a field
      tagValues[i] = tags[i].getEnumConstants();
    }

    tagIdxMultipliers = new int[tags.length];
    if (tags.length != 0) {
      tagIdxMultipliers[0] = 1;
    }
    for (int i = 1; i < tags.length; i++) {
      tagIdxMultipliers[i] =
          tagIdxMultipliers[i - 1]
              * (tagValues[i - 1].length + 1); // +1 to account for "no value" (omitted tag)
    }

    IndexHolder.INDEX = getEndIndex();
  }

  public String getName() {
    return name;
  }

  public Class<? extends TagValue>[] getTags() {
    return tags;
  }

  public int getEndIndex() {
    int delta =
        tagValues.length > 0
            ? (tagIdxMultipliers[tagIdxMultipliers.length - 1]
                * (tagValues[tagValues.length - 1].length + 1))
            : 1;
    return index + delta;
  }

  public CiVisibilityMetricData createData(long value, TagValue... tagValues) {
    return new CiVisibilityMetricData(name, value, tagValues);
  }

  public int getIndex(TagValue... tagValues) {
    int index = this.index;
    for (TagValue tagValue : tagValues) {
      if (tagValue == null) {
        continue;
      }
      index += calculateIdxDelta(tagValue);
    }
    return index;
  }

  private int calculateIdxDelta(TagValue tagValue) {
    Class<? extends TagValue> tag = tagValue.getDeclaringClass();
    for (int i = 0; i < tags.length; i++) {
      if (tag == tags[i]) {
        return tagIdxMultipliers[i]
            * (tagValue.ordinal() + 1); // +1 to account for "no value" (omitted tag)
      }
    }
    throw new IllegalArgumentException(
        "Metric "
            + name()
            + " cannot be tagged with "
            + tag.getSimpleName()
            + ", allowed tags are "
            + Arrays.toString(tags));
  }

  public TagValue[] getTagValues(int index) {
    int tagValueIdx = 0;
    TagValue[] values = new TagValue[tags.length];

    index -= this.index;
    for (int i = 0; i < tags.length; i++) {
      TagValue[] possibleValues = tagValues[i];
      int tagCardinality = possibleValues.length + 1; // +1 to account for "no value" (omitted tag)
      int tagOrdinal = index % tagCardinality;
      // we use 0 to show absence of tag
      if (tagOrdinal != 0) {
        tagOrdinal--; // going back from 1-based to 0-based
        values[tagValueIdx++] = possibleValues[tagOrdinal];
      }
      index /= tagCardinality;
    }

    return tagValueIdx == values.length ? values : Arrays.copyOf(values, tagValueIdx);
  }

  public static int count() {
    return IndexHolder.INDEX;
  }
}
