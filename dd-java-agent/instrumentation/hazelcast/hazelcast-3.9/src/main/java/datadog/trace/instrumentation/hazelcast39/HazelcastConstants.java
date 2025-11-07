package datadog.trace.instrumentation.hazelcast39;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

/** Holder for constant values shared across the instrumentation versions. */
public class HazelcastConstants {

  public static final String INSTRUMENTATION_NAME = "hazelcast";

  public static final boolean DEFAULT_ENABLED = false;

  public static final String HAZELCAST_SERVICE = "hazelcast.service";
  public static final String HAZELCAST_NAME = "hazelcast.name";
  public static final String HAZELCAST_OPERATION = "hazelcast.operation";
  public static final String HAZELCAST_INSTANCE = "hazelcast.instance";
  public static final String HAZELCAST_CORRELATION_ID = "hazelcast.correlationId";

  public static final CharSequence SPAN_NAME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().cache().operation(INSTRUMENTATION_NAME));
  public static final CharSequence COMPONENT_NAME = UTF8BytesString.create("hazelcast-sdk");
}
