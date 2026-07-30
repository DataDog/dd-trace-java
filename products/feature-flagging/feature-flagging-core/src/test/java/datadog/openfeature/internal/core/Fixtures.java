package datadog.openfeature.internal.core;

final class Fixtures {

  static final String UFC =
      "{"
          + "\"createdAt\":\"2026-01-01T00:00:00Z\","
          + "\"format\":\"SERVER\","
          + "\"environment\":{\"name\":\"test\"},"
          + "\"flags\":{"
          + "\"message\":{"
          + "\"key\":\"message\","
          + "\"enabled\":true,"
          + "\"variationType\":\"STRING\","
          + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":\"hello\"}},"
          + "\"allocations\":[{"
          + "\"key\":\"allocation\","
          + "\"rules\":[],"
          + "\"splits\":[{\"variationKey\":\"on\",\"shards\":[],\"serialId\":7}],"
          + "\"doLog\":true"
          + "}]"
          + "}"
          + "}"
          + "}";

  private Fixtures() {}
}
