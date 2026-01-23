package datadog.trace.core.propagation;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH;

import datadog.trace.api.Config;
import datadog.trace.api.ProductTraceSource;
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

  public abstract void forceKeep(int samplingMechanism);

  public abstract int getSamplingPriority();

  public abstract void updateTraceOrigin(CharSequence origin);

  public abstract CharSequence getOrigin();

  public abstract long getTraceIdHighOrderBits();

  public abstract void updateTraceIdHighOrderBits(long highOrderBits);

  public abstract CharSequence getLastParentId();

  public abstract void updateLastParentId(CharSequence lastParentId);

  /**
   * Gets the original <a href="https://www.w3.org/TR/trace-context/#tracestate-header">W3C
   * tracestate header</a> value.
   *
   * @return The original W3C tracestate header value.
   */
  public abstract String getW3CTracestate();

  /**
   * Stores the original <a href="https://www.w3.org/TR/trace-context/#tracestate-header">W3C
   * tracestate header</a> value.
   *
   * @param tracestate The original W3C tracestate header value.
   */
  public abstract void updateW3CTracestate(String tracestate);

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

  /**
   * Updates the trace source to include the specified product.
   *
   * <p>The product value is parsed and interpreted according to the logic in {@link
   * ProductTraceSource}. This method ensures that the given product is marked as part of the trace
   * source.
   *
   * @param product the product identifier to be added to the trace source. Refer to {@link
   *     ProductTraceSource} for details on how the value is interpreted.
   */
  public abstract void addTraceSource(int product);

  /**
   * Retrieves the current trace source.
   *
   * <p>The returned value is an encoded bitfield that represents the included products. To
   * understand how this value is parsed and interpreted, refer to {@link ProductTraceSource}.
   *
   * @return the trace source as an integer bitfield. See {@link ProductTraceSource} for details on
   *     its structure and usage.
   */
  public abstract int getTraceSource();

  public abstract void updateDebugPropagation(String value);

  public abstract String getDebugPropagation();

  public HashMap<String, String> createTagMap() {
    HashMap<String, String> result = new HashMap<>();
    fillTagMap(result);
    return result;
  }

  public abstract void updateAndLockDecisionMaker(PropagationTags source);
}
