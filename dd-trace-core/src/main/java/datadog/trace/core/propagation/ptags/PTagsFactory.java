package datadog.trace.core.propagation.ptags;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static datadog.trace.core.propagation.ptags.PTagsCodec.DECISION_MAKER_TAG;

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
    return createValid(null, null);
  }

  @Override
  public final PropagationTags fromHeaderValue(@Nonnull HeaderType headerType, String value) {
    return DEC_ENC_MAP.get(headerType).fromHeaderValue(this, value);
  }

  PropagationTags createValid(List<TagElement> tagPairs, TagValue decisionMakerTagValue) {
    return new PTags(this, tagPairs, decisionMakerTagValue);
  }

  PropagationTags createInvalid(String error) {
    return new PTagsWithError(this, error);
  }

  static class PTags extends PropagationTags {
    private static final String EMPTY = "";

    protected final PTagsFactory factory;

    // tags that don't require any modifications and propagated as-is
    private final List<TagElement> tagPairs;

    private final boolean canChangeDecisionMaker;

    // extracted decision maker tag for easier updates
    private volatile TagValue decisionMakerTagValue;

    // xDatadogTagsSize of the tagPairs, does not include the decision maker tag
    private volatile int xDatadogTagsSize = -1;

    private volatile int samplingPriority;
    private volatile CharSequence origin;
    private volatile String[] headerCache = null;

    public PTags(PTagsFactory factory, List<TagElement> tagPairs, TagValue decisionMakerTagValue) {
      this(factory, tagPairs, decisionMakerTagValue, PrioritySampling.UNSET, null);
    }

    PTags(
        PTagsFactory factory,
        List<TagElement> tagPairs,
        TagValue decisionMakerTagValue,
        int samplingPriority,
        CharSequence origin) {
      assert tagPairs == null || tagPairs.size() % 2 == 0;
      this.factory = factory;
      this.tagPairs = tagPairs;
      this.canChangeDecisionMaker = decisionMakerTagValue == null;
      this.decisionMakerTagValue = decisionMakerTagValue;
      this.samplingPriority = samplingPriority;
      this.origin = origin;
    }

    @Override
    public void updateTraceSamplingPriority(int samplingPriority, int samplingMechanism) {
      if (samplingPriority != PrioritySampling.UNSET && canChangeDecisionMaker
          || samplingMechanism == SamplingMechanism.EXTERNAL_OVERRIDE) {
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
        xDatadogTagsSize = size;
      }
      return size;
    }

    TagValue getDecisionMakerTagValue() {
      return decisionMakerTagValue;
    }

    String getError() {
      return null;
    }
  }

  static final class PTagsWithError extends PTags {
    final String error;

    public PTagsWithError(PTagsFactory factory, String error) {
      super(factory, null, null, PrioritySampling.UNSET, null);
      this.error = error;
    }

    @Override
    String getError() {
      return error;
    }
  }
}
