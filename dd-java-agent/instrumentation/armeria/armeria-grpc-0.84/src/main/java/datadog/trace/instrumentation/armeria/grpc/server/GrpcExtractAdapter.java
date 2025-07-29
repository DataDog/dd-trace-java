package datadog.trace.instrumentation.armeria.grpc.server;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.grpc.Metadata;

public final class GrpcExtractAdapter implements AgentPropagation.ContextVisitor<Metadata> {

  public static final GrpcExtractAdapter GETTER = new GrpcExtractAdapter();

  @Override
  public void forEachKey(Metadata carrier, AgentPropagation.KeyClassifier classifier) {
    for (String key : carrier.keys()) {
      if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX) && !key.startsWith(":")) {
        if (!classifier.accept(
            key, carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)))) {
          return;
        }
      }
    }
  }
}
