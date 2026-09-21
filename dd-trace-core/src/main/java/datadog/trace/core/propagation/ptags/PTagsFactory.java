package datadog.trace.core.propagation.ptags;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static datadog.trace.core.propagation.ptags.PTagsCodec.DECISION_MAKER_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.KNUTH_SAMPLING_RATE_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.LLMOBS_ML_APP_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.LLMOBS_PAGENT_NAME_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.LLMOBS_PAGENT_SPAN_ID_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.LLMOBS_PARENT_ID_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.LLMOBS_SAMPLE_RATE_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.LLMOBS_SAMPLING_DECISION_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.LLMOBS_SESSION_ID_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.LLMOBS_TRACE_ID_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.ORG_PROPAGATION_MARKER_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.TRACE_ID_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.TRACE_SOURCE_TAG;

import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.internal.util.LongStringUtils;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.propagation.PropagationTags.HeaderType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import javax.annotation.Nonnull;

public class PTagsFactory implements PropagationTags.Factory {
  static final String PROPAGATION_ERROR_TAG_KEY = "_dd.propagation_error";

  private final EnumMap<HeaderType, PTagsCodec> DEC_ENC_MAP = new EnumMap<>(HeaderType.class);

  private final int xDatadogTagsLimit;

  public PTagsFactory(int xDatadogTagsLimit) {
    this.xDatadogTagsLimit = xDatadogTagsLimit;
    DEC_ENC_MAP.put(DATADOG, new DatadogPTagsCodec(xDatadogTagsLimit));
    DEC_ENC_MAP.put(W3C, new W3CPTagsCodec());
  }

  boolean isPropagationTagsDisabled() {
    return xDatadogTagsLimit <= 0;
  }

  int getxDatadogTagsLimit() {
    return xDatadogTagsLimit;
  }

  PTagsCodec getDecoderEncoder(@Nonnull HeaderType headerType) {
    return DEC_ENC_MAP.get(headerType);
  }

  @Override
  public final PropagationTags empty() {
    return createValid(null, null, null, ProductTraceSource.UNSET, null, LLMObsTagValues.EMPTY);
  }

  @Override
  public final PropagationTags fromHeaderValue(@Nonnull HeaderType headerType, String value) {
    return DEC_ENC_MAP.get(headerType).fromHeaderValue(this, value);
  }

  @Override
  public final PropagationTags emptyW3C(String originalTracestate) {
    if (originalTracestate == null || originalTracestate.isEmpty()) {
      return empty();
    }
    return W3CPTagsCodec.empty(this, originalTracestate);
  }

  PropagationTags createValid(
      List<TagElement> tagPairs,
      TagValue decisionMakerTagValue,
      TagValue traceIdTagValue,
      int productTraceSource,
      TagValue orgPropagationMarkerTagValue,
      @Nonnull LLMObsTagValues llmObsTagValues) {
    return new PTags(
        this,
        tagPairs,
        decisionMakerTagValue,
        traceIdTagValue,
        productTraceSource,
        orgPropagationMarkerTagValue,
        llmObsTagValues);
  }

  PropagationTags createInvalid(String error) {
    return PTags.withError(this, error);
  }

  static class PTags extends PropagationTags {
    private static final String EMPTY = "";

    /** What a {@code _dd.p.llmobs_pagent_name=} entry costs before its value: {@code ,_dd.p.k=}. */
    private static final int PAGENT_NAME_ENTRY_OVERHEAD =
        1 + TagElement.Encoding.DATADOG.getPrefixLength() + LLMOBS_PAGENT_NAME_TAG.length() + 1;

    protected final PTagsFactory factory;

    // tags that don't require any modifications and propagated as-is
    private final List<TagElement> tagPairs;

    @SuppressFBWarnings(
        value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
        justification = "This field is never accessed concurrently")
    private boolean canChangeDecisionMaker;

    // extracted decision maker tag for easier updates
    private volatile TagValue decisionMakerTagValue;

    private static final AtomicIntegerFieldUpdater<PTags> TRACE_SOURCE_UPDATER =
        AtomicIntegerFieldUpdater.newUpdater(PTags.class, "traceSource");

    private volatile int traceSource;
    private volatile String debugPropagation;

    private volatile double knuthSamplingRate = Double.NaN;
    private volatile TagValue knuthSamplingRateTagValue;

    private volatile TagValue orgPropagationMarkerTagValue;

    private volatile OtelTraceState otelTraceState;

    /**
     * The LLM Observability propagation tags, held as one immutable bundle. Never {@code null} —
     * {@link LLMObsTagValues#EMPTY} means "none".
     */
    private volatile LLMObsTagValues llmObsTags;

    /**
     * The LLM Observability tags as they arrived on the wire, before anything local staged over
     * them. Never {@code null}.
     *
     * <p>Kept separate because these tags have two writers: the codec, at construction, and the
     * LLMObs propagator, at every injection. Both write {@link #llmObsTags}, and an extracted
     * context's {@code PropagationTags} become the local root's, so without a record of what was
     * extracted the propagator cannot clear its own staging without also deleting the caller's
     * context — silently dropping it at any service that forwards a request without opening an
     * LLMObs span of its own.
     */
    private final LLMObsTagValues extractedLLMObsTags;

    // Static cache for the most-recently-seen rate → TagValue. In steady state a service uses one
    // rate, so this eliminates the char[] + String allocation on every new PTags instance.
    // Writes are benign-racy: two threads computing the same rate produce equal TagValues.
    private static volatile double cachedKsrRate = Double.NaN;
    private static volatile TagValue cachedKsrTagValue;

    // xDatadogTagsSize of the tagPairs, does not include the decision maker tag
    private volatile int xDatadogTagsSize = -1;

    private volatile int samplingPriority;
    private volatile CharSequence origin;
    private volatile String[] headerCache = null;

    /** The high-order 64 bits of the trace id. */
    private volatile long traceIdHighOrderBits;

    /**
     * The zero-padded lower-case 16 character hexadecimal representation of the high-order 64 bits
     * of the trace id, wrapped into a {@link TagValue}, <code>null</code> if not set.
     */
    private volatile TagValue traceIdHighOrderBitsHexTagValue;

    /**
     * The original <a href="https://www.w3.org/TR/trace-context/#tracestate-header">W3C tracestate
     * header</a> value.
     */
    protected volatile String tracestate;

    /**
     * The {@link PTagsFactory#PROPAGATION_ERROR_TAG_KEY propagation tag error} value, {@code null
     * if no error while parsing header}.
     */
    protected volatile String error;

    /**
     * The last parent span id using the 16-characters zero padded hexadecimal representation,
     * {@code null} if not set.
     */
    private volatile CharSequence lastParentId;

    PTags(
        PTagsFactory factory,
        List<TagElement> tagPairs,
        TagValue decisionMakerTagValue,
        TagValue traceIdTagValue,
        int traceSource,
        TagValue orgPropagationMarkerTagValue,
        @Nonnull LLMObsTagValues llmObsTagValues) {
      this(
          factory,
          tagPairs,
          decisionMakerTagValue,
          traceIdTagValue,
          traceSource,
          PrioritySampling.UNSET,
          null,
          null,
          orgPropagationMarkerTagValue,
          llmObsTagValues);
    }

    PTags(
        PTagsFactory factory,
        List<TagElement> tagPairs,
        TagValue decisionMakerTagValue,
        TagValue traceIdTagValue,
        int traceSource,
        int samplingPriority,
        CharSequence origin,
        CharSequence lastParentId,
        TagValue orgPropagationMarkerTagValue,
        @Nonnull LLMObsTagValues llmObsTagValues) {
      assert tagPairs == null || tagPairs.size() % 2 == 0;
      this.factory = factory;
      this.tagPairs = tagPairs;
      this.canChangeDecisionMaker = decisionMakerTagValue == null;
      this.decisionMakerTagValue = decisionMakerTagValue;
      this.traceSource = traceSource;
      this.samplingPriority = samplingPriority;
      this.origin = origin;
      this.lastParentId = lastParentId;
      this.orgPropagationMarkerTagValue = orgPropagationMarkerTagValue;
      this.llmObsTags = llmObsTagValues;
      this.extractedLLMObsTags = llmObsTagValues;
      if (traceIdTagValue != null) {
        CharSequence traceIdHighOrderBitsHex = traceIdTagValue.forType(TagElement.Encoding.DATADOG);
        this.traceIdHighOrderBits =
            LongStringUtils.parseUnsignedLongHex(
                traceIdHighOrderBitsHex, 0, traceIdHighOrderBitsHex.length(), true);
      }
      this.traceIdHighOrderBitsHexTagValue = traceIdTagValue;
      this.error = null;
    }

    static PTags withError(PTagsFactory factory, String error) {
      PTags pTags =
          new PTags(
              factory,
              null,
              null,
              null,
              ProductTraceSource.UNSET,
              PrioritySampling.UNSET,
              null,
              null,
              null,
              LLMObsTagValues.EMPTY);
      pTags.error = error;
      return pTags;
    }

    @Override
    public void updateTraceSamplingPriority(int samplingPriority, int samplingMechanism) {
      if (samplingPriority != PrioritySampling.UNSET && canChangeDecisionMaker
          || samplingMechanism == SamplingMechanism.EXTERNAL_OVERRIDE) {
        doUpdateTraceSamplingPriority(samplingPriority, samplingMechanism);
      }
    }

    @Override
    public void forceKeep(int samplingMechanism) {
      doUpdateTraceSamplingPriority(PrioritySampling.USER_KEEP, samplingMechanism);
    }

    private void doUpdateTraceSamplingPriority(int samplingPriority, int samplingMechanism) {
      if (this.samplingPriority != samplingPriority) {
        // This should invalidate any cached w3c header
        clearCachedHeader(W3C);
      }
      this.samplingPriority = samplingPriority;
      if (samplingPriority > 0) {
        // TODO should try to keep the old sampling mechanism if we override the value?
        if (samplingMechanism == SamplingMechanism.EXTERNAL_OVERRIDE) {
          // There is no specific value for the EXTERNAL_OVERRIDE, so say that it's the DEFAULT
          samplingMechanism = SamplingMechanism.DEFAULT;
        }
        // Protect against possible SamplingMechanism.UNKNOWN (-1) that doesn't comply with the
        // format
        if (samplingMechanism >= 0) {
          TagValue newDM = TagValue.from("-" + samplingMechanism);
          if (!newDM.equals(decisionMakerTagValue)) {
            // This should invalidate any cached w3c and datadog header
            clearCachedHeaders();
          }
          decisionMakerTagValue = newDM;
        }
      } else {
        // Drop the decision maker tag
        if (decisionMakerTagValue != null) {
          // This should invalidate any cached w3c and datadog header
          clearCachedHeaders();
        }
        decisionMakerTagValue = null;
      }
    }

    @Override
    public void addTraceSource(final int product) {
      TRACE_SOURCE_UPDATER.updateAndGet(
          this,
          currentValue -> {
            // If the product is already marked, return the same value (no change)
            if (ProductTraceSource.isProductMarked(currentValue, product)) {
              return currentValue;
            }

            // Invalidate cached headers (atomic context ensures correctness)
            clearCachedHeaders();

            // Set the bit for the given product
            return ProductTraceSource.updateProduct(currentValue, product);
          });
    }

    @Override
    public int getTraceSource() {
      return traceSource;
    }

    @Override
    public void updateDebugPropagation(String value) {
      debugPropagation = value;
    }

    @Override
    public String getDebugPropagation() {
      return debugPropagation;
    }

    @Override
    public void updateKnuthSamplingRate(double rate) {
      if (Double.compare(knuthSamplingRate, rate) != 0) {
        clearCachedHeaders();
        knuthSamplingRate = rate;
        if (Double.isNaN(rate)) {
          knuthSamplingRateTagValue = null;
        } else {
          TagValue tv;
          if (Double.compare(cachedKsrRate, rate) == 0) {
            tv = cachedKsrTagValue;
          } else {
            tv = TagValue.from(formatKnuthSamplingRate(rate));
            cachedKsrTagValue = tv;
            cachedKsrRate = rate;
          }
          knuthSamplingRateTagValue = tv;
        }
      }
    }

    /**
     * Formats a sampling rate with up to 6 decimal digits of precision and no trailing zeros.
     *
     * <p>Values below 0.0000005 (which round to zero at 6 decimal places) return {@code "0"}.
     * Values at or above 0.9999995 return {@code "1"}.
     *
     * <p>Uses char-array arithmetic to avoid {@link java.util.Formatter} allocations entirely.
     */
    static String formatKnuthSamplingRate(double rate) {
      if (rate <= 0.0) return "0";
      if (rate >= 1.0) return "1";

      // Round to 6 decimal places.
      long rounded = Math.round(rate * 1_000_000L);
      if (rounded == 0) return "0";
      if (rounded >= 1_000_000L) return "1";

      // Build "0.DDDDDD" and trim trailing zeros in a single right-to-left pass.
      char[] buf = new char[8]; // "0." + 6 digits
      buf[0] = '0';
      buf[1] = '.';
      int end = 2; // exclusive end; updated on first non-zero digit found from the right
      for (int i = 7; i >= 2; i--) {
        int d = (int) (rounded % 10);
        rounded /= 10;
        buf[i] = (char) ('0' + d);
        if (d != 0 && end == 2) {
          end = i + 1;
        }
      }

      return new String(buf, 0, end);
    }

    TagValue getKnuthSamplingRateTagValue() {
      return knuthSamplingRateTagValue;
    }

    @Override
    public CharSequence getOrgPropagationMarker() {
      return orgPropagationMarkerTagValue;
    }

    @Override
    public void updateOrgPropagationMarker(CharSequence opm) {
      TagValue newValue = opm == null ? null : TagValue.from(opm);
      if (!Objects.equals(this.orgPropagationMarkerTagValue, newValue)) {
        clearCachedHeaders();
        this.orgPropagationMarkerTagValue = newValue;
      }
    }

    TagValue getOrgPropagationMarkerTagValue() {
      return orgPropagationMarkerTagValue;
    }

    @Override
    public void updateLLMObsContext(
        CharSequence traceId,
        CharSequence mlApp,
        CharSequence sessionId,
        CharSequence parentAgentSpanId,
        CharSequence parentAgentName,
        CharSequence parentId,
        CharSequence sampleRate,
        CharSequence samplingDecision) {
      LLMObsTagValues updated =
          LLMObsTagValues.of(
              toTagValue(traceId),
              toTagValue(mlApp),
              toTagValue(sessionId),
              toTagValue(parentAgentSpanId),
              toTagValue(parentAgentName),
              toTagValue(parentId),
              toTagValue(sampleRate),
              toTagValue(samplingDecision));
      if (!updated.equals(llmObsTags)) {
        clearCachedHeaders();
        llmObsTags = updated;
        degradeAgentAttributionToFit();
      }
    }

    /**
     * Trims agent attribution until the {@code x-datadog-tags} tag set fits its configured limit.
     *
     * <p>{@link DatadogPTagsCodec} is all-or-nothing: one byte over the limit and {@code
     * PTagsCodec#headerValue} returns {@code null}, dropping the whole header — the APM tags along
     * with ml_app, session and parent_id. Agent attribution is the only part of the set with a
     * user-supplied, unbounded value (an agent's name), so it is also the only part worth
     * sacrificing to keep the rest. The ladder mirrors {@code _stamp_agent_attribution} in
     * dd-trace-py:
     *
     * <ol>
     *   <li>id and full name, when they fit;
     *   <li>id and a name truncated to the remaining room;
     *   <li>id alone, when no room is left for any of the name;
     *   <li>neither, when even the id overflows.
     * </ol>
     *
     * <p>If the set is still too large with attribution gone, the overflow is somewhere this can't
     * help and the header drops as before. Unlike dd-trace-py, which reserves headroom for a {@code
     * _dd.p.tid} that is added after its check runs, {@link #getXDatadogTagsSize()} already counts
     * every tag, so the full limit is available here.
     *
     * <p>Only the Datadog encoding is guarded. {@link W3CPTagsCodec} rolls back any single tag that
     * would overflow the tracestate and keeps going, so it degrades on its own.
     */
    private void degradeAgentAttributionToFit() {
      LLMObsTagValues tags = llmObsTags;
      if (tags.parentAgentSpanId == null) {
        // Nothing to degrade: a name is only ever written alongside an id.
        return;
      }
      int limit = getxDatadogTagsLimit();
      if (getXDatadogTagsSize() <= limit) {
        return;
      }

      if (tags.parentAgentName != null) {
        // Measure without the name, then give whatever room is left back to a truncated one.
        int sizeWithoutName = applyAgentAttribution(tags.parentAgentSpanId, null);
        if (sizeWithoutName <= limit) {
          int room = limit - sizeWithoutName - PAGENT_NAME_ENTRY_OVERHEAD;
          CharSequence name = tags.parentAgentName.forType(TagElement.Encoding.DATADOG);
          if (room > 0 && room < name.length()) {
            TagValue truncated = toTagValue(name.subSequence(0, room));
            if (truncated != null
                && applyAgentAttribution(tags.parentAgentSpanId, truncated) > limit) {
              // Encoding the truncated value can cost more than its characters; keep id only.
              applyAgentAttribution(tags.parentAgentSpanId, null);
            }
          }
          return;
        }
      }

      // Either there was no name to sacrifice, or the id alone still overflows. Drop attribution
      // rather than lose the whole header.
      applyAgentAttribution(null, null);
    }

    /** Replaces the staged agent attribution and returns the resulting tag set size. */
    private int applyAgentAttribution(TagValue parentAgentSpanId, TagValue parentAgentName) {
      llmObsTags = llmObsTags.withAgentAttribution(parentAgentSpanId, parentAgentName);
      clearCachedHeaders();
      return getXDatadogTagsSize();
    }

    @Override
    public void resetLLMObsContext() {
      if (!extractedLLMObsTags.equals(llmObsTags)) {
        clearCachedHeaders();
        llmObsTags = extractedLLMObsTags;
      }
    }

    @Override
    public CharSequence getLLMObsTraceId() {
      return decoded(extractedLLMObsTags.traceId);
    }

    @Override
    public CharSequence getLLMObsMlApp() {
      return decoded(extractedLLMObsTags.mlApp);
    }

    @Override
    public CharSequence getLLMObsSessionId() {
      return decoded(extractedLLMObsTags.sessionId);
    }

    @Override
    public CharSequence getLLMObsParentAgentSpanId() {
      return decoded(extractedLLMObsTags.parentAgentSpanId);
    }

    @Override
    public CharSequence getLLMObsParentAgentName() {
      return decoded(extractedLLMObsTags.parentAgentName);
    }

    @Override
    public CharSequence getLLMObsParentId() {
      return decoded(extractedLLMObsTags.parentId);
    }

    @Override
    public CharSequence getLLMObsSampleRate() {
      return decoded(extractedLLMObsTags.sampleRate);
    }

    @Override
    public CharSequence getLLMObsSamplingDecision() {
      return decoded(extractedLLMObsTags.samplingDecision);
    }

    /**
     * The value as the application wrote it, undoing the {@code tracestate} substitutions when the
     * value came in on that carrier. {@link TagValue#toString()} would return it in whichever
     * encoding it arrived in, so a {@code ml_app} of {@code a=b} would read back as {@code a~b}
     * after a W3C-only hop. Same conversion {@link PTagsCodec#fillTagMap} applies to every other
     * {@code _dd.p.*} tag.
     */
    private static CharSequence decoded(TagValue value) {
      return value == null ? null : value.forType(TagElement.Encoding.DATADOG);
    }

    LLMObsTagValues getLLMObsTagValues() {
      return llmObsTags;
    }

    /**
     * Wraps a non-empty value as a {@link TagValue}, or {@code null} if it is empty or cannot be
     * represented in {@code x-datadog-tags}.
     *
     * <p>Unlike every other {@code _dd.p.*} tag, these values come from the application rather than
     * the tracer, so they have to be checked before they reach the wire. A value the receiving
     * codec rejects doesn't just lose itself: it fails the whole tagset with {@code decoding_error}
     * and takes {@code _dd.p.tid} with it, leaving the two services disagreeing about the upper 64
     * bits of the trace id. Dropping the one tag is the cheaper loss.
     */
    private static TagValue toTagValue(CharSequence value) {
      if (value == null || value.length() == 0 || !isRepresentable(value)) {
        return null;
      }
      return TagValue.from(value);
    }

    /**
     * Whether every character survives each carrier the value can travel on: printable ASCII, no
     * {@code ,} (the {@code x-datadog-tags} separator), nothing the {@code tracestate} conversion
     * rewrites, and no {@code "} or {@code \} — AWS messaging carries these headers in a {@code
     * _datadog} JSON attribute that is written and parsed without escaping.
     */
    private static boolean isRepresentable(CharSequence value) {
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (c < ' '
            || c > '~'
            || c == ','
            || c == '"'
            || c == '\\'
            || !TagValue.survivesW3CRoundTrip(c)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public int getSamplingPriority() {
      return samplingPriority;
    }

    @Override
    public void updateTraceOrigin(CharSequence origin) {
      // TODO we should really have UTF8ByteStrings for the regular ones
      CharSequence existing = this.origin;
      if (Objects.equals(existing, origin)) {
        return;
      }
      // Invalidate any cached w3c header
      clearCachedHeader(W3C);
      this.origin = TagValue.from(origin);
    }

    @Override
    public CharSequence getOrigin() {
      return origin;
    }

    @Override
    public long getTraceIdHighOrderBits() {
      return traceIdHighOrderBits;
    }

    public void updateTraceIdHighOrderBits(long highOrderBits) {
      if (traceIdHighOrderBits != highOrderBits) {
        traceIdHighOrderBits = highOrderBits;
        traceIdHighOrderBitsHexTagValue =
            highOrderBits == 0
                ? null
                : TagValue.from(LongStringUtils.toHexStringPadded(highOrderBits, 16));
        clearCachedHeader(DATADOG);
      }
    }

    @Override
    public CharSequence getLastParentId() {
      return lastParentId;
    }

    @Override
    @SuppressWarnings("StringEquality")
    @SuppressFBWarnings("ES_COMPARING_STRINGS_WITH_EQ")
    public String headerValue(HeaderType headerType) {
      String header = getCachedHeader(headerType);
      if (header == null) {
        header = PTagsCodec.headerValue(factory.getDecoderEncoder(headerType), this);
        if (header != null) {
          setCachedHeader(headerType, header);
        } else {
          // We can still cache the fact that we got back null
          setCachedHeader(headerType, EMPTY);
        }
      }
      if (header == EMPTY) {
        return null;
      }
      return header;
    }

    @Override
    public String headerValue(HeaderType headerType, CharSequence lastParentIdOverride) {
      if (lastParentIdOverride == null) {
        return headerValue(headerType);
      }
      // Inject-time path: encode fresh with the override; do NOT cache — the W3C `p:` is
      // per-injecting-span and these tags may be shared across sibling spans.
      String header =
          PTagsCodec.headerValue(factory.getDecoderEncoder(headerType), this, lastParentIdOverride);
      return (header == null || header.isEmpty()) ? null : header;
    }

    @Override
    public void fillTagMap(Map<String, String> tagMap) {
      PTagsCodec.fillTagMap(this, tagMap);
    }

    private String getCachedHeader(HeaderType headerType) {
      String[] cache = headerCache;
      if (cache == null) {
        return null;
      }
      return cache[headerType.ordinal()];
    }

    private void setCachedHeader(HeaderType headerType, String header) {
      String[] cache = headerCache;
      if (cache == null) {
        cache = headerCache = new String[HeaderType.getNumValues()];
      }
      cache[headerType.ordinal()] = header;
    }

    /**
     * Invalidate every encoding's cached header, and the memoized x-datadog-tags size with them.
     * Use this whenever a change affects both wire formats.
     */
    private void clearCachedHeaders() {
      clearCachedHeader(DATADOG);
      clearCachedHeader(W3C);
    }

    private void clearCachedHeader(HeaderType headerType) {
      if (headerType == DATADOG) {
        invalidateXDatadogTagsSize();
      }
      String[] cache = headerCache;
      if (cache == null) {
        return;
      }
      cache[headerType.ordinal()] = null;
    }

    int getxDatadogTagsLimit() {
      return factory.getxDatadogTagsLimit();
    }

    boolean isPropagationTagsDisabled() {
      return factory.isPropagationTagsDisabled();
    }

    List<TagElement> getTagPairs() {
      return tagPairs == null ? Collections.emptyList() : tagPairs;
    }

    private void invalidateXDatadogTagsSize() {
      this.xDatadogTagsSize = -1;
    }

    int getXDatadogTagsSize() {
      int size = xDatadogTagsSize;
      if (size == -1) {
        size = PTagsCodec.calcXDatadogTagsSize(getTagPairs());
        size = PTagsCodec.calcXDatadogTagsSize(size, DECISION_MAKER_TAG, decisionMakerTagValue);
        size = PTagsCodec.calcXDatadogTagsSize(size, TRACE_ID_TAG, traceIdHighOrderBitsHexTagValue);
        size =
            PTagsCodec.calcXDatadogTagsSize(
                size, KNUTH_SAMPLING_RATE_TAG, getKnuthSamplingRateTagValue());
        size =
            PTagsCodec.calcXDatadogTagsSize(
                size, ORG_PROPAGATION_MARKER_TAG, getOrgPropagationMarkerTagValue());
        LLMObsTagValues currentLLMObsTags = llmObsTags;
        size =
            PTagsCodec.calcXDatadogTagsSize(size, LLMOBS_TRACE_ID_TAG, currentLLMObsTags.traceId);
        size = PTagsCodec.calcXDatadogTagsSize(size, LLMOBS_ML_APP_TAG, currentLLMObsTags.mlApp);
        size =
            PTagsCodec.calcXDatadogTagsSize(
                size, LLMOBS_SESSION_ID_TAG, currentLLMObsTags.sessionId);
        size =
            PTagsCodec.calcXDatadogTagsSize(
                size, LLMOBS_PAGENT_SPAN_ID_TAG, currentLLMObsTags.parentAgentSpanId);
        size =
            PTagsCodec.calcXDatadogTagsSize(
                size, LLMOBS_PAGENT_NAME_TAG, currentLLMObsTags.parentAgentName);
        size =
            PTagsCodec.calcXDatadogTagsSize(size, LLMOBS_PARENT_ID_TAG, currentLLMObsTags.parentId);
        size =
            PTagsCodec.calcXDatadogTagsSize(
                size, LLMOBS_SAMPLE_RATE_TAG, currentLLMObsTags.sampleRate);
        size =
            PTagsCodec.calcXDatadogTagsSize(
                size, LLMOBS_SAMPLING_DECISION_TAG, currentLLMObsTags.samplingDecision);
        int currentProductTraceSource = traceSource;
        if (currentProductTraceSource != ProductTraceSource.UNSET) {
          size =
              PTagsCodec.calcXDatadogTagsSize(
                  size,
                  TRACE_SOURCE_TAG,
                  TagValue.from(ProductTraceSource.getBitfieldHex(currentProductTraceSource)));
        }
        xDatadogTagsSize = size;
      }
      return size;
    }

    TagValue getTraceIdHighOrderBitsHexTagValue() {
      return traceIdHighOrderBitsHexTagValue;
    }

    TagValue getDecisionMakerTagValue() {
      return decisionMakerTagValue;
    }

    @Override
    public String getW3CTracestate() {
      return this.tracestate;
    }

    @Override
    public void updateW3CTracestate(String tracestate) {
      setW3CTracestate(tracestate, W3CPTagsCodec.extractOtelTraceState(tracestate));
    }

    @Override
    public void updateW3CTracestateFrom(PropagationTags source) {
      if (!(source instanceof PTags)) {
        super.updateW3CTracestateFrom(source);
        return;
      }
      PTags sourcePTags = (PTags) source;
      setW3CTracestate(sourcePTags.tracestate, sourcePTags.getOtelTraceState());
    }

    private void setW3CTracestate(String tracestate, OtelTraceState otelTraceState) {
      clearCachedHeader(W3C);
      this.tracestate = tracestate;
      this.otelTraceState = otelTraceState;
    }

    OtelTraceState getOtelTraceState() {
      return otelTraceState;
    }

    void setOtelTraceState(OtelTraceState otelTraceState) {
      if (this.otelTraceState != otelTraceState) {
        this.otelTraceState = otelTraceState;
        clearCachedHeader(W3C);
      }
    }

    String getError() {
      return this.error;
    }

    @Override
    public void updateAndLockDecisionMaker(PropagationTags source) {
      if (source instanceof PTags) {
        canChangeDecisionMaker = false;
        decisionMakerTagValue = ((PTags) source).getDecisionMakerTagValue();
        if (decisionMakerTagValue != null) {
          clearCachedHeaders();
        }
      }
    }
  }
}
