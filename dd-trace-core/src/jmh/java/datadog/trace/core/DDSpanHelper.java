package datadog.trace.core;

import java.util.Map;
import javax.annotation.Nonnull;

public interface DDSpanHelper {
  static DDSpan create(final long timestampMicro, @Nonnull DDSpanContext context) {
    return DDSpan.create(timestampMicro, context);
  }

  static void setAllTags(@Nonnull DDSpanContext context, Map<String, ?> map) {
    context.setAllTags(map);
  }
}
