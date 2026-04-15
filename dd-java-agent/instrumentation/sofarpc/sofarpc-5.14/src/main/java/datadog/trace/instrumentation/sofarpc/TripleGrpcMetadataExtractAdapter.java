package datadog.trace.instrumentation.sofarpc;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.grpc.Metadata;

/**
 * Extracts Datadog propagation headers from gRPC {@link Metadata}.
 *
 * <p>Used by {@link TripleServerInstrumentation} to read the trace context that was injected by
 * {@link AbstractClusterInstrumentation} into {@code SofaRequest.requestProps} on the client side
 * and then serialised into gRPC Metadata by SOFA RPC's {@code TripleTracerAdapter.beforeSend()}.
 *
 * <p>Identical in structure to {@code GrpcExtractAdapter} in the grpc-1.5 instrumentation.
 */
public final class TripleGrpcMetadataExtractAdapter
    implements AgentPropagation.ContextVisitor<Metadata> {

  public static final TripleGrpcMetadataExtractAdapter GETTER =
      new TripleGrpcMetadataExtractAdapter();

  @Override
  public void forEachKey(Metadata carrier, AgentPropagation.KeyClassifier classifier) {
    for (String key : carrier.keys()) {
      if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        if (!classifier.accept(
            key, carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)))) {
          return;
        }
      }
    }
  }
}
