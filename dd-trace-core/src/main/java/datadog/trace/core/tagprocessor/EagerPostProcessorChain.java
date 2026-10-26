package datadog.trace.core.tagprocessor;

import datadog.trace.api.TagStore;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.core.DDSpanContext;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Runs eager post-processors in order. */
public final class EagerPostProcessorChain extends EagerTagsPostProcessor {
  private final EagerTagsPostProcessor[] chain;

  public EagerPostProcessorChain(@Nonnull final EagerTagsPostProcessor... processors) {
    chain = Objects.requireNonNull(processors);
  }

  @Override
  public void processTags(
      TagStore storage, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    for (final EagerTagsPostProcessor processor : chain) {
      processor.processTags(storage, spanContext, spanLinks);
    }
  }
}
