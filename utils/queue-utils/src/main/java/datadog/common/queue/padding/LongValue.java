package datadog.common.queue.padding;

/** Holds the actual long value, padded on left to prevent false sharing. */
class LongValue extends LhsPadding {
  protected volatile long value;
}
