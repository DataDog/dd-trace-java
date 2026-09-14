package datadog.trace.core.propagation.ptags;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static datadog.trace.core.propagation.ptags.PTagsCodec.DECISION_MAKER_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.KNUTH_SAMPLING_RATE_TAG;
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
    return createValid(null, null, null, ProductTraceSource.UNSET, null);
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
      TagValue orgPropagationMarkerTagValue) {
    return new PTags(
        this,
        tagPairs,
        decisionMakerTagValue,
        traceIdTagValue,
        productTraceSource,
        orgPropagationMarkerTagValue);
  }

  PropagationTags createInvalid(String error) {
    return PTags.withError(this, error);
  }

  static class PTags extends PropagationTags {
    private static final String EMPTY = "";

    protected final PTagsFactory factory;

    // tags that don't require any modifications and propagated as-is
    private final List<TagElement> tagPairs;

    @SuppressFBWarnings(
        value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
        justification = "This field is never accessed concurrently")
    private boolean canChangeDecisionMaker;

    private static final AtomicIntegerFieldUpdater<PTags> TRACE_SOURCE_UPDATER =
        AtomicIntegerFieldUpdater.newUpdater(PTags.class, "traceSource");

    private volatile int traceSource;
    private volatile String debugPropagation;

    private volatile TagValue orgPropagationMarkerTagValue;

    private OtelTraceState otelTraceState;
    private volatile SamplingState samplingState;

    // Static cache for the most-recently-seen rate → TagValue. In steady state a service uses one
    // rate, so this eliminates the char[] + String allocation on every new PTags instance.
    // Writes are benign-racy: two threads computing the same rate produce equal TagValues.
    private static volatile double cachedKsrRate = Double.NaN;
    private static volatile TagValue cachedKsrTagValue;

    private volatile SizeCacheEntry xDatadogTagsSizeCache;

    private volatile CharSequence origin;
    private volatile HeaderCacheEntry datadogHeaderCache;
    private volatile HeaderCacheEntry w3cHeaderCache;

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
        TagValue orgPropagationMarkerTagValue) {
      this(
          factory,
          tagPairs,
          decisionMakerTagValue,
          traceIdTagValue,
          traceSource,
          PrioritySampling.UNSET,
          null,
          null,
          orgPropagationMarkerTagValue);
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
        TagValue orgPropagationMarkerTagValue) {
      assert tagPairs == null || tagPairs.size() % 2 == 0;
      this.factory = factory;
      this.tagPairs = tagPairs;
      this.canChangeDecisionMaker = decisionMakerTagValue == null;
      this.traceSource = traceSource;
      this.samplingState =
          newSamplingState(samplingPriority, null, null, decisionMakerTagValue, null);
      this.origin = origin;
      this.lastParentId = lastParentId;
      this.orgPropagationMarkerTagValue = orgPropagationMarkerTagValue;
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
              null);
      pTags.error = error;
      return pTags;
    }

    @Override
    public synchronized void updateTraceSamplingPriority(
        int samplingPriority, int samplingMechanism) {
      if (samplingPriority != PrioritySampling.UNSET && canChangeDecisionMaker
          || samplingMechanism == SamplingMechanism.EXTERNAL_OVERRIDE) {
        OtelTraceState nextOtelTraceState = otelTraceState;
        if (nextOtelTraceState != null) {
          if (samplingMechanism == SamplingMechanism.EXTERNAL_OVERRIDE
              && !nextOtelTraceState.isConsistentWith(samplingPriority > 0)) {
            nextOtelTraceState = nextOtelTraceState.withoutThreshold();
          } else if (samplingMechanism != SamplingMechanism.UNKNOWN
              && samplingMechanism != SamplingMechanism.EXTERNAL_OVERRIDE) {
            nextOtelTraceState = nextOtelTraceState.forNonProbabilityDecision();
          }
        }
        installSamplingState(samplingPriority, samplingMechanism, nextOtelTraceState);
      }
    }

    @Override
    public synchronized boolean tryUpdateTraceSamplingPriority(
        int samplingPriority, int samplingMechanism, boolean allowOverride) {
      if (samplingPriority == PrioritySampling.UNSET) {
        return false;
      }
      SamplingState current = samplingState;
      if (!allowOverride && current.getSamplingPriority() != PrioritySampling.UNSET) {
        return false;
      }
      OtelTraceState nextOtelTraceState = otelTraceState;
      if (nextOtelTraceState != null) {
        if ((samplingMechanism == SamplingMechanism.EXTERNAL_OVERRIDE
                || samplingMechanism == SamplingMechanism.UNKNOWN)
            && !nextOtelTraceState.isConsistentWith(samplingPriority > 0)) {
          nextOtelTraceState = nextOtelTraceState.withoutThreshold();
        } else if (samplingMechanism != SamplingMechanism.UNKNOWN) {
          nextOtelTraceState = nextOtelTraceState.forNonProbabilityDecision();
        }
      }
      installSamplingState(samplingPriority, samplingMechanism, nextOtelTraceState);
      return true;
    }

    @Override
    public synchronized boolean tryUpdateProbabilitySamplingDecision(
        int samplingPriority,
        int samplingMechanism,
        double sampleRate,
        boolean probabilitySamplingResult,
        long traceIdLowOrderBits,
        boolean allowOverride) {
      SamplingState current = samplingState;
      if (!allowOverride && current.getSamplingPriority() != PrioritySampling.UNSET) {
        return false;
      }
      OtelTraceState nextOtelTraceState = otelTraceState;
      if (nextOtelTraceState == null) {
        boolean limiterDemotion = probabilitySamplingResult && samplingPriority <= 0;
        if (!limiterDemotion) {
          nextOtelTraceState =
              OtelTraceState.fromProbabilityDecision(
                  traceIdLowOrderBits, sampleRate, probabilitySamplingResult);
        }
      } else if (probabilitySamplingResult && samplingPriority <= 0) {
        nextOtelTraceState = nextOtelTraceState.withoutThreshold();
      }
      TagValue nextKnuthSamplingRate = knuthSamplingRateTagValue(sampleRate);
      installSamplingState(
          samplingPriority, samplingMechanism, nextOtelTraceState, nextKnuthSamplingRate);
      return true;
    }

    @Override
    public synchronized void forceKeep(int samplingMechanism) {
      OtelTraceState nextOtelTraceState = otelTraceState;
      if (nextOtelTraceState != null) {
        nextOtelTraceState = nextOtelTraceState.forNonProbabilityDecision();
      }
      installSamplingState(PrioritySampling.USER_KEEP, samplingMechanism, nextOtelTraceState);
    }

    private void installSamplingState(
        int samplingPriority, int samplingMechanism, OtelTraceState nextOtelTraceState) {
      installSamplingState(
          samplingPriority, samplingMechanism, nextOtelTraceState, getKnuthSamplingRateTagValue());
    }

    private void installSamplingState(
        int samplingPriority,
        int samplingMechanism,
        OtelTraceState nextOtelTraceState,
        TagValue nextKnuthSamplingRateTagValue) {
      clearCachedHeader(W3C);
      TagValue nextDecisionMakerTagValue = getDecisionMakerTagValue();
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
          if (!newDM.equals(nextDecisionMakerTagValue)) {
            // This should invalidate any cached w3c and datadog header
            clearCachedHeader(DATADOG);
            clearCachedHeader(W3C);
          }
          nextDecisionMakerTagValue = newDM;
        }
      } else {
        // Drop the decision maker tag
        if (nextDecisionMakerTagValue != null) {
          // This should invalidate any cached w3c and datadog header
          clearCachedHeader(DATADOG);
          clearCachedHeader(W3C);
        }
        nextDecisionMakerTagValue = null;
      }
      otelTraceState = nextOtelTraceState;
      samplingState =
          newSamplingState(
              samplingPriority,
              tracestate,
              nextOtelTraceState,
              nextDecisionMakerTagValue,
              nextKnuthSamplingRateTagValue);
    }

    private static SamplingState newSamplingState(
        int samplingPriority,
        String tracestate,
        OtelTraceState otelTraceState,
        TagValue decisionMakerTagValue,
        TagValue knuthSamplingRateTagValue) {
      return new SamplingState(
          samplingPriority,
          tracestate,
          otelTraceState,
          decisionMakerTagValue,
          knuthSamplingRateTagValue);
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
            clearCachedHeader(DATADOG);
            clearCachedHeader(W3C);

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
    public synchronized void updateKnuthSamplingRate(double rate) {
      TagValue current = getKnuthSamplingRateTagValue();
      TagValue next = knuthSamplingRateTagValue(rate);
      if (!Objects.equals(current, next)) {
        clearCachedHeader(DATADOG);
        clearCachedHeader(W3C);
        SamplingState currentState = samplingState;
        samplingState =
            newSamplingState(
                currentState.getSamplingPriority(),
                tracestate,
                otelTraceState,
                getDecisionMakerTagValue(currentState),
                next);
      }
    }

    private static TagValue knuthSamplingRateTagValue(double rate) {
      if (Double.isNaN(rate)) {
        return null;
      }
      if (Double.compare(cachedKsrRate, rate) == 0) {
        return cachedKsrTagValue;
      }
      TagValue value = TagValue.from(formatKnuthSamplingRate(rate));
      cachedKsrTagValue = value;
      cachedKsrRate = rate;
      return value;
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
      return getKnuthSamplingRateTagValue(samplingState);
    }

    TagValue getKnuthSamplingRateTagValue(SamplingState samplingState) {
      return asTagValue(samplingState.getKnuthSamplingRate());
    }

    @Override
    public CharSequence getOrgPropagationMarker() {
      return orgPropagationMarkerTagValue;
    }

    @Override
    public void updateOrgPropagationMarker(CharSequence opm) {
      TagValue newValue = opm == null ? null : TagValue.from(opm);
      if (!Objects.equals(this.orgPropagationMarkerTagValue, newValue)) {
        clearCachedHeader(DATADOG);
        clearCachedHeader(W3C);
        this.orgPropagationMarkerTagValue = newValue;
      }
    }

    TagValue getOrgPropagationMarkerTagValue() {
      return orgPropagationMarkerTagValue;
    }

    @Override
    public int getSamplingPriority() {
      return samplingState.getSamplingPriority();
    }

    @Override
    public SamplingState samplingState() {
      return samplingState;
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
      SamplingState currentSamplingState = samplingState;
      String header = getCachedHeader(headerType, currentSamplingState);
      if (header == null) {
        header =
            PTagsCodec.headerValue(
                factory.getDecoderEncoder(headerType), this, null, currentSamplingState);
        if (header != null) {
          setCachedHeader(headerType, currentSamplingState, header);
        } else {
          // We can still cache the fact that we got back null
          setCachedHeader(headerType, currentSamplingState, EMPTY);
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
      String header =
          PTagsCodec.headerValue(factory.getDecoderEncoder(headerType), this, lastParentIdOverride);
      return (header == null || header.isEmpty()) ? null : header;
    }

    @Override
    public String headerValue(
        HeaderType headerType, CharSequence lastParentIdOverride, SamplingState samplingState) {
      String header =
          PTagsCodec.headerValue(
              factory.getDecoderEncoder(headerType), this, lastParentIdOverride, samplingState);
      return (header == null || header.isEmpty()) ? null : header;
    }

    @Override
    public void fillTagMap(Map<String, String> tagMap) {
      PTagsCodec.fillTagMap(this, tagMap);
    }

    private String getCachedHeader(HeaderType headerType, SamplingState samplingState) {
      HeaderCacheEntry cache = headerType == DATADOG ? datadogHeaderCache : w3cHeaderCache;
      return cache != null && cache.samplingState == samplingState ? cache.header : null;
    }

    private void setCachedHeader(
        HeaderType headerType, SamplingState samplingState, String header) {
      HeaderCacheEntry entry = new HeaderCacheEntry(samplingState, header);
      if (headerType == DATADOG) {
        datadogHeaderCache = entry;
      } else {
        w3cHeaderCache = entry;
      }
    }

    private void clearCachedHeader(HeaderType headerType) {
      if (headerType == DATADOG) {
        invalidateXDatadogTagsSize();
      }
      if (headerType == DATADOG) {
        datadogHeaderCache = null;
      } else {
        w3cHeaderCache = null;
      }
    }

    private static final class HeaderCacheEntry {
      private final SamplingState samplingState;
      private final String header;

      private HeaderCacheEntry(SamplingState samplingState, String header) {
        this.samplingState = samplingState;
        this.header = header;
      }
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
      xDatadogTagsSizeCache = null;
    }

    int getXDatadogTagsSize() {
      return getXDatadogTagsSize(samplingState);
    }

    int getXDatadogTagsSize(SamplingState samplingState) {
      SizeCacheEntry cache = xDatadogTagsSizeCache;
      if (cache == null || cache.samplingState != samplingState) {
        int size = PTagsCodec.calcXDatadogTagsSize(getTagPairs());
        size =
            PTagsCodec.calcXDatadogTagsSize(
                size, DECISION_MAKER_TAG, getDecisionMakerTagValue(samplingState));
        size = PTagsCodec.calcXDatadogTagsSize(size, TRACE_ID_TAG, traceIdHighOrderBitsHexTagValue);
        size =
            PTagsCodec.calcXDatadogTagsSize(
                size, KNUTH_SAMPLING_RATE_TAG, getKnuthSamplingRateTagValue(samplingState));
        size =
            PTagsCodec.calcXDatadogTagsSize(
                size, ORG_PROPAGATION_MARKER_TAG, getOrgPropagationMarkerTagValue());
        int currentProductTraceSource = traceSource;
        if (currentProductTraceSource != ProductTraceSource.UNSET) {
          size =
              PTagsCodec.calcXDatadogTagsSize(
                  size,
                  TRACE_SOURCE_TAG,
                  TagValue.from(ProductTraceSource.getBitfieldHex(currentProductTraceSource)));
        }
        cache = new SizeCacheEntry(samplingState, size);
        xDatadogTagsSizeCache = cache;
      }
      return cache.size;
    }

    private static final class SizeCacheEntry {
      private final SamplingState samplingState;
      private final int size;

      private SizeCacheEntry(SamplingState samplingState, int size) {
        this.samplingState = samplingState;
        this.size = size;
      }
    }

    TagValue getTraceIdHighOrderBitsHexTagValue() {
      return traceIdHighOrderBitsHexTagValue;
    }

    TagValue getDecisionMakerTagValue() {
      return getDecisionMakerTagValue(samplingState);
    }

    TagValue getDecisionMakerTagValue(SamplingState samplingState) {
      return asTagValue(samplingState.getDecisionMaker());
    }

    private static TagValue asTagValue(CharSequence value) {
      if (value == null) {
        return null;
      }
      return value instanceof TagValue ? (TagValue) value : TagValue.from(value);
    }

    @Override
    public String getW3CTracestate() {
      return this.tracestate;
    }

    @Override
    public String getW3CTracestate(SamplingState samplingState) {
      return samplingState.getTracestate();
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
      SamplingState sourceState = sourcePTags.samplingState();
      CharSequence sourceOtelTraceState = sourceState.getOtelTraceState();
      setW3CTracestate(
          sourceState.getTracestate(),
          sourceOtelTraceState instanceof OtelTraceState
              ? (OtelTraceState) sourceOtelTraceState
              : W3CPTagsCodec.extractOtelTraceState(sourceState.getTracestate()));
    }

    private synchronized void setW3CTracestate(String tracestate, OtelTraceState otelTraceState) {
      clearCachedHeader(W3C);
      int samplingPriority = samplingState.getSamplingPriority();
      this.tracestate = tracestate;
      this.otelTraceState = otelTraceState;
      this.samplingState =
          newSamplingState(
              samplingPriority,
              tracestate,
              otelTraceState,
              getDecisionMakerTagValue(),
              getKnuthSamplingRateTagValue());
    }

    OtelTraceState getOtelTraceState() {
      return otelTraceState;
    }

    void setOtelTraceState(OtelTraceState otelTraceState) {
      if (this.otelTraceState != otelTraceState) {
        clearCachedHeader(W3C);
      }
      this.otelTraceState = otelTraceState;
      SamplingState currentState = samplingState;
      this.samplingState =
          newSamplingState(
              currentState.getSamplingPriority(),
              tracestate,
              otelTraceState,
              getDecisionMakerTagValue(currentState),
              getKnuthSamplingRateTagValue(currentState));
    }

    String getError() {
      return this.error;
    }

    @Override
    public synchronized void updateAndLockDecisionMaker(PropagationTags source) {
      if (source instanceof PTags) {
        canChangeDecisionMaker = false;
        TagValue decisionMakerTagValue = ((PTags) source).getDecisionMakerTagValue();
        if (decisionMakerTagValue != null) {
          clearCachedHeader(DATADOG);
          clearCachedHeader(W3C);
        }
        SamplingState currentState = samplingState;
        samplingState =
            newSamplingState(
                currentState.getSamplingPriority(),
                tracestate,
                otelTraceState,
                decisionMakerTagValue,
                getKnuthSamplingRateTagValue(currentState));
      }
    }
  }
}
