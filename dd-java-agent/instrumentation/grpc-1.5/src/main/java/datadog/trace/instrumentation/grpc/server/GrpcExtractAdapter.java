package datadog.trace.instrumentation.grpc.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.grpc.Metadata;

public final class GrpcExtractAdapter implements AgentPropagation.ContextVisitor<Metadata> {

  public static final GrpcExtractAdapter GETTER = new GrpcExtractAdapter();

  @Override
  public void forEachKey(
      Metadata carrier,
      AgentPropagation.KeyClassifier classifier,
      AgentPropagation.KeyValueConsumer consumer) {
    for (String key : carrier.keys()) {
      if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        String lowerCaseKey = key.toLowerCase();
        int classification = classifier.classify(lowerCaseKey);
        if (classification != IGNORE) {
          String value = carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
          if (!consumer.accept(classification, lowerCaseKey, value)) {
            return;
          }
        }
      }
    }
  }
}
