package com.datadog.profiling.otel.proto;

/**
 * OTLP Profiles protobuf field numbers. Based on
 * https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/profiles/v1development/profiles.proto
 */
public final class OtlpProtoFields {

  private OtlpProtoFields() {}

  // ProfilesData fields
  public static final class ProfilesData {
    public static final int RESOURCE_PROFILES = 1;
    public static final int DICTIONARY = 2;

    private ProfilesData() {}
  }

  // ProfilesDictionary fields
  public static final class ProfilesDictionary {
    public static final int MAPPING_TABLE = 1;
    public static final int LOCATION_TABLE = 2;
    public static final int FUNCTION_TABLE = 3;
    public static final int LINK_TABLE = 4;
    public static final int STRING_TABLE = 5;
    public static final int ATTRIBUTE_TABLE = 6;
    public static final int STACK_TABLE = 7;

    private ProfilesDictionary() {}
  }

  // ResourceProfiles fields
  public static final class ResourceProfiles {
    public static final int RESOURCE = 1;
    public static final int SCOPE_PROFILES = 2;
    public static final int SCHEMA_URL = 3;

    private ResourceProfiles() {}
  }

  // ScopeProfiles fields
  public static final class ScopeProfiles {
    public static final int SCOPE = 1;
    public static final int PROFILES = 2;
    public static final int SCHEMA_URL = 3;

    private ScopeProfiles() {}
  }

  // Profile fields
  public static final class Profile {
    public static final int SAMPLE_TYPE = 1;
    public static final int SAMPLES = 2;
    public static final int TIME_UNIX_NANO = 3;
    public static final int DURATION_NANO = 4;
    public static final int PERIOD_TYPE = 5;
    public static final int PERIOD = 6;
    public static final int PROFILE_ID = 7;
    public static final int DROPPED_ATTRIBUTES_COUNT = 8;
    public static final int ORIGINAL_PAYLOAD_FORMAT = 9;
    public static final int ORIGINAL_PAYLOAD = 10;
    public static final int ATTRIBUTE_INDICES = 11;

    private Profile() {}
  }

  // Sample fields
  public static final class Sample {
    public static final int STACK_INDEX = 1;
    public static final int VALUES = 2;
    public static final int ATTRIBUTE_INDICES = 3;
    public static final int LINK_INDEX = 4;
    public static final int TIMESTAMPS_UNIX_NANO = 5;

    private Sample() {}
  }

  // ValueType fields
  public static final class ValueType {
    public static final int TYPE_STRINDEX = 1;
    public static final int UNIT_STRINDEX = 2;

    private ValueType() {}
  }

  // Mapping fields
  public static final class Mapping {
    public static final int MEMORY_START = 1;
    public static final int MEMORY_LIMIT = 2;
    public static final int FILE_OFFSET = 3;
    public static final int FILENAME_STRINDEX = 4;
    public static final int ATTRIBUTE_INDICES = 5;

    private Mapping() {}
  }

  // Location fields
  public static final class Location {
    public static final int MAPPING_INDEX = 1;
    public static final int ADDRESS = 2;
    public static final int LINES = 3;
    public static final int ATTRIBUTE_INDICES = 4;

    private Location() {}
  }

  // Line fields
  public static final class Line {
    public static final int FUNCTION_INDEX = 1;
    public static final int LINE = 2;
    public static final int COLUMN = 3;

    private Line() {}
  }

  // Function fields
  public static final class Function {
    public static final int NAME_STRINDEX = 1;
    public static final int SYSTEM_NAME_STRINDEX = 2;
    public static final int FILENAME_STRINDEX = 3;
    public static final int START_LINE = 4;

    private Function() {}
  }

  // Stack fields
  public static final class Stack {
    public static final int LOCATION_INDICES = 1;

    private Stack() {}
  }

  // Link fields
  public static final class Link {
    public static final int TRACE_ID = 1;
    public static final int SPAN_ID = 2;

    private Link() {}
  }

  // KeyValueAndUnit fields
  public static final class KeyValueAndUnit {
    public static final int KEY_STRINDEX = 1;
    public static final int VALUE = 2;
    public static final int UNIT_STRINDEX = 3;

    private KeyValueAndUnit() {}
  }

  // AnyValue fields (from common.proto)
  public static final class AnyValue {
    public static final int STRING_VALUE = 1;
    public static final int BOOL_VALUE = 2;
    public static final int INT_VALUE = 3;
    public static final int DOUBLE_VALUE = 4;

    private AnyValue() {}
  }

  // Resource fields (from resource.proto)
  public static final class Resource {
    public static final int ATTRIBUTES = 1;
    public static final int DROPPED_ATTRIBUTES_COUNT = 2;

    private Resource() {}
  }

  // KeyValue fields (from common.proto)
  public static final class KeyValue {
    public static final int KEY = 1;
    public static final int VALUE = 2;

    private KeyValue() {}
  }

  // InstrumentationScope fields (from common.proto)
  public static final class InstrumentationScope {
    public static final int NAME = 1;
    public static final int VERSION = 2;
    public static final int ATTRIBUTES = 3;
    public static final int DROPPED_ATTRIBUTES_COUNT = 4;

    private InstrumentationScope() {}
  }
}
