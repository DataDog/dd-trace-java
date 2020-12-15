package datadog.trace.instrumentation.springcloudzuul2;

import datadog.trace.api.Function;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.Pair;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class ResourceNameCache {
  public static final Function<Pair<String, Object>, CharSequence> RESOURCE_NAME_JOINER =
      new Function<Pair<String, Object>, CharSequence>() {
        @Override
        public CharSequence apply(Pair<String, Object> input) {
          return UTF8BytesString.create(input.getLeft() + " " + input.getRight());
        }
      };

  public static final DDCache<Pair<String, Object>, CharSequence> RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(64);
}
