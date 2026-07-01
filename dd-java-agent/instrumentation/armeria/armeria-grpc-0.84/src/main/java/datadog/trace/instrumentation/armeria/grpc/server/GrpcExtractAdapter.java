package datadog.trace.instrumentation.armeria.grpc.server;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.grpc.Metadata;
import java.util.function.Function;

public final class GrpcExtractAdapter implements AgentPropagation.ContextVisitor<Metadata> {
  private static final Function<String, Metadata.Key<String>> KEY_MAKER =
      key -> Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
  private static final DDCache<String, Metadata.Key<String>> KEY_CACHE =
      DDCaches.newFixedSizeCache(64);

  public static final GrpcExtractAdapter GETTER = new GrpcExtractAdapter();

  @Override
  public void forEachKey(Metadata carrier, AgentPropagation.KeyClassifier classifier) {
    for (String key : carrier.keys()) {
      if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX) && !key.startsWith(":")) {
        if (!classifier.accept(key, carrier.get(KEY_CACHE.computeIfAbsent(key, KEY_MAKER)))) {
          return;
        }
      }
    }
  }
}
