package datadog.trace.core.tagprocessor;

import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

public class PostProcessorChain implements TagsPostProcessor {
  private final TagsPostProcessor[] chain;

  public PostProcessorChain(@Nonnull final TagsPostProcessor... processors) {
    chain = Objects.requireNonNull(processors);
  }

  @Override
  public Map<String, Object> processTags(Map<String, Object> unsafeTags) {
    Map<String, Object> currentTags = unsafeTags;
    for (final TagsPostProcessor tagsPostProcessor : chain) {
      currentTags = tagsPostProcessor.processTags(currentTags);
    }
    return currentTags;
  }
}
