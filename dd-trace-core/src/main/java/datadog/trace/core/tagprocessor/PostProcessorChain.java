package datadog.trace.core.tagprocessor;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

public class PostProcessorChain implements TagsPostProcessor {
  private final TagsPostProcessor[] chain;

  public PostProcessorChain(@Nonnull final TagsPostProcessor... processors) {
    chain = Objects.requireNonNull(processors);
  }

  @Override
  public Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    Map<String, Object> currentTags = unsafeTags;
    for (final TagsPostProcessor tagsPostProcessor : chain) {
      currentTags = tagsPostProcessor.processTags(currentTags, spanContext, spanLinks);
    }
    return currentTags;
  }
}
