package datadog.trace.api.civisibility.telemetry;

import datadog.trace.api.civisibility.telemetry.tag.Command;
import datadog.trace.api.civisibility.telemetry.tag.Endpoint;
import datadog.trace.api.civisibility.telemetry.tag.ResponseCompressed;
import datadog.trace.api.telemetry.MetricCollector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum CiVisibilityDistributionMetric {

  /** The size in bytes of the serialized payload */
  ENDPOINT_PAYLOAD_BYTES("endpoint_payload.bytes", Endpoint.class),
  /** The time it takes to send the payload sent to the endpoint in ms */
  ENDPOINT_PAYLOAD_REQUESTS_MS("endpoint_payload.requests_ms", Endpoint.class),
  /** The number of events included in the payload */
  ENDPOINT_PAYLOAD_EVENTS_COUNT("endpoint_payload.events_count", Endpoint.class),
  /** The time it takes to serialize the payload */
  ENDPOINT_PAYLOAD_EVENTS_SERIALIZATION_MS(
      "endpoint_payload.events_serialization_ms", Endpoint.class),
  /** The time it takes to execute a git command */
  GIT_COMMAND_MS("git.command_ms", Command.class),
  /** The time it takes to get the response of the search commit request in ms */
  GIT_REQUESTS_SEARCH_COMMITS_MS("git_requests.search_commits_ms"),
  /** The time it takes to get the response of the git object pack request in ms */
  GIT_REQUESTS_OBJECTS_PACK_MS("git_requests.objects_pack_ms"),
  /** The sum of the sizes of the object pack files inside a single payload */
  GIT_REQUESTS_OBJECTS_PACK_BYTES("git_requests.objects_pack_bytes"),
  /** The number of pack files created in a test session */
  GIT_REQUESTS_OBJECTS_PACK_FILES("git_requests.objects_pack_files"),
  /** The time it takes to get the response of the settings endpoint request in ms */
  GIT_REQUESTS_SETTINGS_MS("git_requests.settings_ms"),
  /** The time it takes to get the response of the itr skippable tests endpoint request in ms */
  ITR_SKIPPABLE_TESTS_REQUEST_MS("itr_skippable_tests.request_ms"),
  /** The number of bytes received by the skippable tests endpoint */
  ITR_SKIPPABLE_TESTS_RESPONSE_BYTES(
      "itr_skippable_tests.response_bytes", ResponseCompressed.class),
  /** The number of files covered inside a coverage payload */
  CODE_COVERAGE_FILES("code_coverage.files"),
  /** The time it takes to get the response of the known tests endpoint request in ms */
  KNOWN_TESTS_REQUEST_MS("known_tests.request_ms"),
  /** The number of bytes received by the known tests endpoint */
  KNOWN_TESTS_RESPONSE_BYTES("known_tests.response_bytes", ResponseCompressed.class),
  /** The number of tests received by the known tests endpoint */
  KNOWN_TESTS_RESPONSE_TESTS("known_tests.response_tests"),
  /** The time it takes to get the response of the flaky tests endpoint request in ms */
  FLAKY_TESTS_REQUEST_MS("flaky_tests.request_ms"),
  /** The number of bytes received by the flaky tests endpoint */
  FLAKY_TESTS_RESPONSE_BYTES("flaky_tests.response_bytes", ResponseCompressed.class),
  /** The number of tests received by the flaky tests endpoint */
  FLAKY_TESTS_RESPONSE_TESTS("flaky_tests.response_tests"),
  /** The time it takes to get the response of the test management tests endpoint request in ms */
  TEST_MANAGEMENT_TESTS_REQUEST_MS("test_management.request_ms"),
  /** The number of bytes received by the test management tests endpoint */
  TEST_MANAGEMENT_TESTS_RESPONSE_BYTES("test_management.response_bytes", ResponseCompressed.class),
  /** The number of tests received by the test management tests endpoint */
  TEST_MANAGEMENT_TESTS_RESPONSE_TESTS("test_management.response_tests"),
  /** The time it takes to make a coverage upload request in ms */
  COVERAGE_UPLOAD_REQUEST_MS("coverage_upload.request_ms"),
  /** The size of a coverage upload request in bytes */
  COVERAGE_UPLOAD_REQUEST_BYTES("coverage_upload.request_bytes", ResponseCompressed.class);

  private static final String NAMESPACE = "civisibility";

  private final String name;
  private final Class<? extends TagValue>[] tags;

  @SafeVarargs
  CiVisibilityDistributionMetric(String metricName, Class<? extends TagValue>... metricTags) {
    name = metricName;
    tags = metricTags;
  }

  public String getName() {
    return name;
  }

  public MetricCollector.DistributionSeriesPoint createDataPoint(int value, TagValue... tagValues) {
    List<String> tags;
    if (tagValues.length != 0) {
      tags = new ArrayList<>(tagValues.length);
      for (TagValue tagValue : tagValues) {
        if (tagValue == null) {
          continue;
        }
        assertValid(tagValue);
        tags.add(tagValue.asString());
      }
    } else {
      tags = Collections.emptyList();
    }
    return new MetricCollector.DistributionSeriesPoint(name, true, NAMESPACE, value, tags);
  }

  private void assertValid(TagValue tagValue) {
    Class<? extends TagValue> tag = tagValue.getDeclaringClass();
    for (Class<? extends TagValue> aClass : tags) {
      if (tag == aClass) {
        return;
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
}
