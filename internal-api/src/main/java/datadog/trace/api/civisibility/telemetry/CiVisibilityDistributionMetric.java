package datadog.trace.api.civisibility.telemetry;

import datadog.trace.api.civisibility.telemetry.tag.Command;
import datadog.trace.api.civisibility.telemetry.tag.Endpoint;
import java.util.Arrays;

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
  /** The number of files sent in the object pack payload */
  GIT_REQUESTS_OBJECTS_PACK_FILES("git_requests.objects_pack_files"),
  /** The time it takes to get the response of the settings endpoint request in ms */
  GIT_REQUESTS_SETTINGS_MS("git_requests.settings_ms"),
  /** The time it takes to get the response of the itr skippable tests endpoint request in ms */
  ITR_SKIPPABLE_TESTS_REQUEST_MS("itr_skippable_tests.request_ms"),
  /** The number of bytes received by the skippable tests endpoint */
  ITR_SKIPPABLE_TESTS_RESPONSE_BYTES("itr_skippable_tests.response_bytes"),
  /** The number of files covered inside a coverage payload */
  CODE_COVERAGE_FILES("code_coverage.files");

  private final String name;
  private final Class<? extends TagValue>[] tags;

  @SafeVarargs
  CiVisibilityDistributionMetric(String metricName, Class<? extends TagValue>... metricTags) {
    name = metricName;
    tags = metricTags;
  }

  String getName() {
    return name;
  }

  CiVisibilityMetricData createData(long value, TagValue... tagValues) {
    for (TagValue tagValue : tagValues) {
      assertValid(tagValue);
    }
    return new CiVisibilityMetricData(
        name, CiVisibilityMetricData.Type.DISTRIBUTION, value, tagValues);
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
