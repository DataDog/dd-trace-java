package datadog.trace.core.propagation;

import java.util.Map;

/** Noop implementation of DatadogTags. It's used when x-datadog-tags propagation is disabled. */
class DatadogTagsNoop extends DatadogTags {

  static DatadogTags INSTANCE = new DatadogTagsNoop();

  private DatadogTagsNoop() {}

  @Override
  public void updateUpstreamServices(String serviceName, int priority, int mechanism, double rate) {
    // noop impl, do nothing
  }

  @Override
  public boolean isEmpty() {
    // always empty
    return true;
  }

  @Override
  public String encodeAsHeaderValue() {
    return "";
  }

  @Override
  public Map<String, String> parseAndMerge() {
    return null;
  }
}
