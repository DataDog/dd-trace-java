package datadog.trace.api.http;

import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.function.Function;

public class HttpResourceNames {

  private static final Function<Pair<CharSequence, CharSequence>, UTF8BytesString> JOINER =
      input -> {
        if (input.getLeft() == null) {
          return UTF8BytesString.create(input.getRight());
        }
        return UTF8BytesString.create(
            input.getLeft().toString().toUpperCase() + " " + input.getRight());
      };

  private static final DDCache<Pair<CharSequence, CharSequence>, CharSequence> CACHE =
      DDCaches.newFixedSizeCache(128);

  public static CharSequence compute(CharSequence method, CharSequence path) {
    return CACHE.computeIfAbsent(Pair.of(method, path), JOINER);
  }
}
