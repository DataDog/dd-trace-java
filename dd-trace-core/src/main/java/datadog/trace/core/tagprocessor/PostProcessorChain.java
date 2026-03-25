package datadog.trace.core.tagprocessor;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.WritableSpanLinks;
import datadog.trace.core.DDSpanContext;
import java.util.Objects;
import javax.annotation.Nonnull;

public final class PostProcessorChain extends TagsPostProcessor {
  private final TagsPostProcessor[] chain;

  public PostProcessorChain(@Nonnull final TagsPostProcessor... processors) {
    chain = Objects.requireNonNull(processors);
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, WritableSpanLinks spanLinks) {
    for (final TagsPostProcessor tagsPostProcessor : chain) {
      tagsPostProcessor.processTags(unsafeTags, spanContext, spanLinks);
    }
  }
}
