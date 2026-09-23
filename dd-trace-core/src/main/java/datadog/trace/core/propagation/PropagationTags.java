package datadog.trace.core.propagation;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH;

import datadog.trace.api.Config;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.llmobs.LLMObsPropagationValues;
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

    /**
     * Returns a fresh PropagationTags that re-encodes only the non-{@code dd} vendor sections of
     * the supplied W3C tracestate. All Datadog-side state (sampling priority, origin, {@code
     * _dd.p.*} tags) is dropped. If {@code originalTracestate} is {@code null} or empty, behaves
     * like {@link #empty()}.
     */
    PropagationTags emptyW3C(String originalTracestate);
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

  /** Updates the original W3C tracestate header from {@code source}. */
  public void updateW3CTracestateFrom(PropagationTags source) {
    updateW3CTracestate(source.getW3CTracestate());
  }

  /**
   * Constructs a header value that includes valid propagated _dd.p.* tags and possibly a new
   * sampling decision tag _dd.p.dm based on the current state. Returns null if the value length
   * exceeds a configured limit or empty.
   */
  public abstract String headerValue(HeaderType headerType);

  /**
   * Like {@link #headerValue(HeaderType)} but uses {@code lastParentIdOverride} for the W3C {@code
   * p:} (last-parent-id) instead of the stored {@link #getLastParentId() last-parent-id}. Used at
   * inject so the injecting span's id is supplied as a parameter rather than mutated into these
   * (possibly trace-level, shared) tags — keeping transient per-injection identity out of shared
   * state. A {@code null} override falls back to {@link #headerValue(HeaderType)}.
   */
  public abstract String headerValue(HeaderType headerType, CharSequence lastParentIdOverride);

  /**
   * Like {@link #headerValue(HeaderType, CharSequence)} but also writes the {@code _dd.p.llmobs_*}
   * tags from {@code llmObsValues}.
   *
   * <p>Threaded in for the same reason as {@code lastParentIdOverride}: these values belong to the
   * span being injected, while these tags can be shared by every span in a local trace, so holding
   * them here would let concurrent injections serialize each other's LLM Observability context. A
   * {@code null} {@code llmObsValues} writes the values that arrived on the inbound headers, which
   * is what a service forwarding a request without an LLMObs span of its own should propagate.
   */
  public abstract String headerValue(
      HeaderType headerType,
      CharSequence lastParentIdOverride,
      LLMObsPropagationValues llmObsValues);

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

  /**
   * Updates the Knuth sampling rate (_dd.p.ksr) propagated tag. This records the sampling rate that
   * was applied when making an agent-based or rule-based sampling decision. The rate is formatted
   * with up to 6 significant digits and no trailing zeros, matching the Go/Python reference
   * implementations (%.6g format).
   *
   * @param rate the sampling rate value
   */
  public abstract void updateKnuthSamplingRate(double rate);

  /**
   * Returns the Org Propagation Marker (OPM) currently held in these tags, encoded as {@code
   * _dd.p.opm} in Datadog headers and {@code t.opm} in W3C tracestate. Returns {@code null} if no
   * OPM is set.
   */
  public abstract CharSequence getOrgPropagationMarker();

  /**
   * Sets the Org Propagation Marker (OPM). Passing {@code null} clears the marker. The injection
   * codecs call this just before serializing so that, when the local tracer knows its own OPM, it
   * overrides any inbound OPM.
   */
  public abstract void updateOrgPropagationMarker(CharSequence opm);

  /**
   * Returns the LLM Observability values that arrived on the inbound headers as {@code
   * _dd.p.llmobs_*}, or {@code null} if none did. Individual fields are {@code null} when their tag
   * was absent, and carry the value as the application wrote it, with any {@code tracestate}
   * substitutions undone.
   *
   * <p>This reads what was <em>extracted</em>, never what a local injection staged over it. The two
   * live in the same object — an extracted context's tags become the local root's — but only the
   * extracted half is a statement about the caller. A local LLMObs span's tags stay staged until
   * the next injection resets them, so a sibling span opened in that window would otherwise read a
   * finished span's attribution as if it had come from upstream.
   *
   * <p>Two fields carry more than their name suggests. The LLMObs trace id is distinct from the APM
   * trace id: an LLMObs trace spans only the services that produce LLMObs spans, so it survives
   * intermediate hops that start a new APM trace and it stays stable when one APM trace carries
   * several LLMObs traces; it is carried on the wire as an unsigned 128-bit <em>decimal</em>
   * integer, the format dd-trace-py writes and parses. And the sample rate is the one that produced
   * the accompanying sampling decision ({@code "1"} retained, {@code "0"} dropped) upstream, not
   * this service's configured rate — honouring the pair keeps a distributed LLMObs trace whole
   * across services configured at different rates, which re-rolling locally would not.
   */
  public abstract LLMObsPropagationValues getExtractedLLMObsValues();

  public HashMap<String, String> createTagMap() {
    HashMap<String, String> result = new HashMap<>();
    fillTagMap(result);
    return result;
  }

  public abstract void updateAndLockDecisionMaker(PropagationTags source);
}
