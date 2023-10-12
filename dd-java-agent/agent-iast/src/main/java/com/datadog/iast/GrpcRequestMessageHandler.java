package com.datadog.iast;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;

public class GrpcRequestMessageHandler implements BiFunction<RequestContext, Object, Flow<Void>> {

  /**
   * This will cover:
   *
   * <ul>
   *   <li>com.google.protobuf.GeneratedMessage
   *   <li>com.google.protobuf.GeneratedMessageV3
   *   <li>com.google.protobuf.GeneratedMessageLite
   * </ul>
   */
  private static final String GENERATED_MESSAGE = "com.google.protobuf.GeneratedMessage";

  /** Maps map to this class that does not implement Map interface */
  private static final String MAP_FIELD = "com.google.protobuf.MapField";

  @Override
  public Flow<Void> apply(final RequestContext ctx, final Object o) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null && o != null) {
      final IastRequestContext iastCtx = ctx.getData(RequestContextSlot.IAST);
      module.taintDeeply(
          iastCtx, SourceTypes.GRPC_BODY, o, GrpcRequestMessageHandler::isProtobufArtifact);
    }
    return Flow.ResultFlow.empty();
  }

  static boolean isProtobufArtifact(@Nonnull final Class<?> kls) {
    return kls.getSuperclass().getName().startsWith(GENERATED_MESSAGE)
        || MAP_FIELD.equals(kls.getName());
  }
}
