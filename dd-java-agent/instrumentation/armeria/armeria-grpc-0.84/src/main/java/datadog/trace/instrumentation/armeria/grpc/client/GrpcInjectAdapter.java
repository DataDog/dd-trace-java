package datadog.trace.instrumentation.armeria.grpc.client;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import io.grpc.Metadata;
import java.util.function.Function;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public final class GrpcInjectAdapter implements CarrierSetter<Metadata> {
  private static final Function<String, Metadata.Key<String>> KEY_MAKER =
      key -> Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
  private static final DDCache<String, Metadata.Key<String>> KEY_CACHE =
      DDCaches.newFixedSizeCache(64);

  public static final GrpcInjectAdapter SETTER = new GrpcInjectAdapter();

  @Override
  public void set(final Metadata carrier, final String key, final String value) {
    Metadata.Key<String> metadataKey = KEY_CACHE.computeIfAbsent(key, KEY_MAKER);
    if (carrier.containsKey(metadataKey)) {
      carrier.removeAll(
          metadataKey); // Remove existing to ensure identical behavior with other carriers
    }
    carrier.put(metadataKey, value);
  }
}
