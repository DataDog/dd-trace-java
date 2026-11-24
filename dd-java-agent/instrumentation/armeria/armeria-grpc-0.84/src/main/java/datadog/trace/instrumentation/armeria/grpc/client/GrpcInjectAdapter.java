package datadog.trace.instrumentation.armeria.grpc.client;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import io.grpc.Metadata;
import java.util.function.Function;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
public final class GrpcInjectAdapter implements CarrierSetter<Metadata> {
  private static final Logger log = LoggerFactory.getLogger(GrpcInjectAdapter.class);

  /**
   * Cached for header names gRPC rejects, such as baggage keys with characters gRPC does not allow,
   * so that each is skipped instead of throwing and aborting the rest of the injection.
   */
  private static final Metadata.Key<String> INVALID_KEY =
      Metadata.Key.of("x-datadog-invalid-key", Metadata.ASCII_STRING_MARSHALLER);

  private static final Function<String, Metadata.Key<String>> KEY_MAKER =
      GrpcInjectAdapter::makeKey;
  private static final DDCache<String, Metadata.Key<String>> KEY_CACHE =
      DDCaches.newFixedSizeCache(64);

  public static final GrpcInjectAdapter SETTER = new GrpcInjectAdapter();

  @Override
  public void set(final Metadata carrier, final String key, final String value) {
    Metadata.Key<String> metadataKey = KEY_CACHE.computeIfAbsent(key, KEY_MAKER);
    if (metadataKey == INVALID_KEY) {
      return;
    }
    if (carrier.containsKey(metadataKey)) {
      carrier.removeAll(
          metadataKey); // Remove existing to ensure identical behavior with other carriers
    }
    carrier.put(metadataKey, value);
  }

  private static Metadata.Key<String> makeKey(String key) {
    try {
      return Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
    } catch (IllegalArgumentException e) {
      log.debug(EXCLUDE_TELEMETRY, "Skipping header not allowed in gRPC metadata: {}", key, e);
      return INVALID_KEY;
    }
  }
}
