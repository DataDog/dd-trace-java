package datadog.trace.core.propagation.ptags;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static datadog.trace.core.propagation.ptags.PTagsCodec.DECISION_MAKER_TAG;
import static datadog.trace.core.propagation.ptags.PTagsCodec.KNUTH_SAMPLING_RATE_TAG;
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
    return createValid(null, null, null, ProductTraceSource.UNSET);
  }

  @Override
  public final PropagationTags fromHeaderValue(@Nonnull HeaderType headerType, String value) {
    return DEC_ENC_MAP.get(headerType).fromHeaderValue(this, value);
  }

  PropagationTags createValid(
      List<TagElement> tagPairs,
      TagValue decisionMakerTagValue,
      TagValue traceIdTagValue,
      int productTraceSource) {
    return new PTags(this, tagPairs, decisionMakerTagValue, traceIdTagValue, productTraceSource);
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

    // extracted decision maker tag for easier updates
    private volatile TagValue decisionMakerTagValue;

    private static final AtomicIntegerFieldUpdater<PTags> TRACE_SOURCE_UPDATER =
        AtomicIntegerFieldUpdater.newUpdater(PTags.class, "traceSource");

    private volatile int traceSource;
    private volatile String debugPropagation;

    private volatile double knuthSamplingRate = Double.NaN;
    private volatile TagValue knuthSamplingRateTagValue;

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

    public PTags(
        PTagsFactory factory,
        List<TagElement> tagPairs,
        TagValue decisionMakerTagValue,
        TagValue traceIdTagValue,
        int traceSource) {
      this(
          factory,
          tagPairs,
          decisionMakerTagValue,
          traceIdTagValue,
          traceSource,
          PrioritySampling.UNSET,
          null,
          null);
    }

    PTags(
        PTagsFactory factory,
        List<TagElement> tagPairs,
        TagValue decisionMakerTagValue,
        TagValue traceIdTagValue,
        int traceSource,
        int samplingPriority,
        CharSequence origin,
        CharSequence lastParentId) {
      assert tagPairs == null || tagPairs.size() % 2 == 0;
      this.factory = factory;
      this.tagPairs = tagPairs;
      this.canChangeDecisionMaker = decisionMakerTagValue == null;
      this.decisionMakerTagValue = decisionMakerTagValue;
      this.traceSource = traceSource;
      this.samplingPriority = samplingPriority;
      this.origin = origin;
      this.lastParentId = lastParentId;
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
              null);
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
            clearCachedHeader(DATADOG);
            clearCachedHeader(W3C);
          }
          decisionMakerTagValue = newDM;
        }
      } else {
        // Drop the decision maker tag
        if (decisionMakerTagValue != null) {
          // This should invalidate any cached w3c and datadog header
          clearCachedHeader(DATADOG);
          clearCachedHeader(W3C);
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
    public void updateKnuthSamplingRate(double rate) {
      if (Double.compare(knuthSamplingRate, rate) != 0) {
        clearCachedHeader(DATADOG);
        clearCachedHeader(W3C);
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
    public void updateLastParentId(CharSequence lastParentId) {
      if (!Objects.equals(this.lastParentId, lastParentId)) {
        clearCachedHeader(W3C);
        this.lastParentId = TagValue.from(lastParentId);
      }
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
      this.tracestate = tracestate;
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
          clearCachedHeader(DATADOG);
          clearCachedHeader(W3C);
        }
      }
    }
  }
}
