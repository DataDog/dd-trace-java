package com.datadog.iast;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
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
      final IastContext iastCtx = ctx.getData(RequestContextSlot.IAST);
      final byte source = SourceTypes.GRPC_BODY;
      final int tainted =
          module.taintObjectDeeply(
              iastCtx, o, source, GrpcRequestMessageHandler::isProtobufArtifact);
      if (tainted > 0) {
        IastMetricCollector.add(IastMetric.EXECUTED_SOURCE, source, tainted, iastCtx);
      }
    }
    return Flow.ResultFlow.empty();
  }

  static boolean isProtobufArtifact(@Nonnull final Class<?> kls) {
    return kls.getSuperclass().getName().startsWith(GENERATED_MESSAGE)
        || MAP_FIELD.equals(kls.getName());
  }
}
