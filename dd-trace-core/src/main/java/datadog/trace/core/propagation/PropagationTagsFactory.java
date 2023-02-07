package datadog.trace.core.propagation;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.core.propagation.PropagationTags.HeaderType;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PropagationTagsFactory implements PropagationTags.Factory {
  static final String PROPAGATION_ERROR_TAG_KEY = "_dd.propagation_error";

  private final DatadogPropagationTagsFactory ddFactory;

  PropagationTagsFactory(int xDatadogTagsLimit) {
    ddFactory = new DatadogPropagationTagsFactory(xDatadogTagsLimit);
  }

  @Override
  public final PropagationTags empty() {
    return new ValidPropagationTags(Collections.<String>emptyList(), 0, false);
  }

  @Override
  public final PropagationTags fromHeaderValue(HeaderType headerType, String value) {
    return ddFactory.fromHeaderValue(this, value);
  }

  PropagationTags createValid(List<String> tagPairs, int tagSize, boolean hasDecisionMaker) {
    return new ValidPropagationTags(tagPairs, tagSize, hasDecisionMaker);
  }

  PropagationTags createInvalid(String error) {
    return new InvalidPropagationTags(error);
  }

  // This implementation is used when service propagation is enabled
  private final class ValidPropagationTags extends PropagationTags {
    // tags that don't require any modifications and propagated as-is
    private final List<String> propagatedTagPairs;
    // pre-calc header size
    private final int propagatedTagsSize;

    private final boolean isDecisionMakerTagMissing;

    // extracted decision maker tag for easier updates
    private volatile String decisionMakerTagValue;

    private ValidPropagationTags(List<String> tagPairs, int tagsSize, boolean hasDecisionMaker) {
      assert tagPairs.size() % 2 == 0;
      propagatedTagPairs = tagPairs;
      propagatedTagsSize = tagsSize;
      isDecisionMakerTagMissing = !hasDecisionMaker;
    }

    @Override
    public void updateTraceSamplingPriority(int samplingPriority, int samplingMechanism) {

      if (samplingPriority != PrioritySampling.UNSET && isDecisionMakerTagMissing) {
        if (samplingPriority > 0) {
          // protected against possible SamplingMechanism.UNKNOWN (-1) that doesn't comply with the
          // format
          if (samplingMechanism >= 0) {
            decisionMakerTagValue = "-" + samplingMechanism;
          }
        } else {
          // drop decision maker tag
          decisionMakerTagValue = null;
        }
      }
    }

    @Override
    public String headerValue(HeaderType headerType) {
      return ddFactory.headerValue(this);
    }

    @Override
    public void fillTagMap(Map<String, String> tagMap) {
      ddFactory.fillTagMap(this, tagMap);
    }

    @Override
    List<String> tagPairs() {
      return propagatedTagPairs;
    }

    @Override
    int tagsSize() {
      return propagatedTagsSize;
    }

    @Override
    boolean missingDecisionMaker() {
      return isDecisionMakerTagMissing;
    }

    @Override
    String decisionMakerTagValue() {
      return decisionMakerTagValue;
    }
  }

  // This implementation is used for errors and doesn't allow any modifications
  private static final class InvalidPropagationTags extends PropagationTags {
    private final String error;

    private InvalidPropagationTags(String error) {
      this.error = error;
    }

    @Override
    public void updateTraceSamplingPriority(int samplingPriority, int samplingMechanism) {}

    @Override
    public String headerValue(HeaderType headerType) {
      return null;
    }

    @Override
    public void fillTagMap(Map<String, String> tagMap) {
      tagMap.put(PROPAGATION_ERROR_TAG_KEY, error);
    }

    @Override
    List<String> tagPairs() {
      return Collections.emptyList();
    }

    @Override
    int tagsSize() {
      return 0;
    }

    @Override
    boolean missingDecisionMaker() {
      return false;
    }

    @Override
    String decisionMakerTagValue() {
      return null;
    }
  }
}
