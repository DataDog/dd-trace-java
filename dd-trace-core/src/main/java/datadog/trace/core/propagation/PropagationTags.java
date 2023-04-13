package datadog.trace.core.propagation;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH;

import datadog.trace.api.Config;
import datadog.trace.core.propagation.ptags.PTagsFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * Encapsulates logic related to the Service Propagation including:
 *
 * <pre>
 *   - parsing and validation the x-datadog-tags header value
 *   - dropping non _dd.p.* tags
 *   - error handling and propagation
 *   - concurrent updates to the sampling priority
 *   - producing the x-datadog-tags header value
 *   - producing meta tags to be sent to the agent
 * </pre>
 */
public abstract class PropagationTags {

  public static PropagationTags.Factory factory(Config config) {
    return factory(config.getxDatadogTagsMaxLength());
  }

  public static PropagationTags.Factory factory(int datadogTagsLimit) {
    return new PTagsFactory(datadogTagsLimit);
  }

  public static PropagationTags.Factory factory() {
    return factory(DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH);
  }

  public enum HeaderType {
    DATADOG,
    W3C;

    private static final int numValues = HeaderType.values().length;

    public static int getNumValues() {
      return numValues;
    }
  }

  public interface Factory {
    PropagationTags empty();

    PropagationTags fromHeaderValue(HeaderType headerType, String value);
  }

  /**
   * Updates the trace-level sampling priority decision if it hasn't already been made and _dd.p.dm
   * tag doesn't exist. Called on the root span context.
   */
  public abstract void updateTraceSamplingPriority(int samplingPriority, int samplingMechanism);

  public abstract int getSamplingPriority();

  public abstract void updateTraceOrigin(CharSequence origin);

  public abstract CharSequence getOrigin();

  public abstract long getTraceIdHighOrderBits();

  public abstract void updateTraceIdHighOrderBits(long highOrderBits);

  /**
   * Constructs a header value that includes valid propagated _dd.p.* tags and possibly a new
   * sampling decision tag _dd.p.dm based on the current state. Returns null if the value length
   * exceeds a configured limit or empty.
   */
  public abstract String headerValue(HeaderType headerType);

  /**
   * Fills a provided tagMap with valid propagated _dd.p.* tags and possibly a new sampling decision
   * tags _dd.p.dm (root span only) based on the current state, or sets only an error tag if the
   * header value exceeds a configured limit.
   */
  public abstract void fillTagMap(Map<String, String> tagMap);

  public HashMap<String, String> createTagMap() {
    HashMap<String, String> result = new HashMap<>();
    fillTagMap(result);
    return result;
  }
}
