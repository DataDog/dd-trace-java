package datadog.trace.bootstrap.instrumentation.api;

public class InstrumentationTags {

  // this exists to make it easy to intern UTF-8 encoding
  // of tag/metric keys used in instrumentations. It should
  // stay reasonably small. TODO when/if this gets large
  // start looking at generating constants based on the
  // enabled instrumentations.

  public static final String PARTITION = "partition";
  public static final String OFFSET = "offset";
  public static final String RECORD_QUEUE_TIME_MS = "record.queue_time_ms";
}
