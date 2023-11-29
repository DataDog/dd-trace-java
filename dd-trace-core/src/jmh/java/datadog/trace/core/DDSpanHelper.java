package datadog.trace.core;

import java.util.Map;
import javax.annotation.Nonnull;

public interface DDSpanHelper {
  static DDSpan create(
      @Nonnull String instrumentationName,
      final long timestampMicro,
      @Nonnull DDSpanContext context) {
    return DDSpan.create(instrumentationName, timestampMicro, context, null);
  }

  static void setAllTags(@Nonnull DDSpanContext context, Map<String, ?> map) {
    context.setAllTags(map);
  }
}
