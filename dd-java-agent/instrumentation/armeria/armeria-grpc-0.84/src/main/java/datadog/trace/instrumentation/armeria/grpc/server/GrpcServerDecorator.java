package datadog.trace.instrumentation.armeria.grpc.server;

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ServerDecorator;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.util.BitSet;
import java.util.function.Function;

public class GrpcServerDecorator extends ServerDecorator {

  private static final boolean TRIM_RESOURCE_PACKAGE_NAME =
      Config.get().isGrpcServerTrimPackageResource();
  private static final BitSet SERVER_ERROR_STATUSES = Config.get().getGrpcServerErrorStatuses();

  public static final CharSequence GRPC_SERVER =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().server().operationForProtocol("grpc"));
  public static final CharSequence COMPONENT_NAME = UTF8BytesString.create("armeria-grpc-server");
  public static final CharSequence GRPC_MESSAGE = UTF8BytesString.create("grpc.message");

  private static DataStreamsTags createServerPathwaySortedTags() {
    return DataStreamsTags.create("grpc", INBOUND);
  }

  public static final DataStreamsTags SERVER_PATHWAY_EDGE_TAGS = createServerPathwaySortedTags();
  public static final GrpcServerDecorator DECORATE = new GrpcServerDecorator();

  private static final Function<String, String> NORMALIZE =
      // Uses inner class for predictable name for Instrumenter.Default.helperClassNames()
      new Function<String, String>() {
        @Override
        public String apply(String fullName) {
          int index = fullName.lastIndexOf(".");
          if (index > 0) {
            return fullName.substring(index + 1);
          } else {
            return fullName;
          }
        }
      };

  private final DDCache<String, String> cachedResourceNames;

  public GrpcServerDecorator() {
    if (TRIM_RESOURCE_PACKAGE_NAME) {
      cachedResourceNames = DDCaches.newFixedSizeCache(512);
    } else {
      cachedResourceNames = null;
    }
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"armeria-grpc-server", "armeria-grpc", "armeria", "grpc-server", "grpc"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.RPC;
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setMeasured(true);
    return super.afterStart(span);
  }

  public <RespT, ReqT> AgentSpan onCall(final AgentSpan span, ServerCall<ReqT, RespT> call) {
    if (TRIM_RESOURCE_PACKAGE_NAME) {
      span.setResourceName(
          cachedResourceNames.computeIfAbsent(
              call.getMethodDescriptor().getFullMethodName(), NORMALIZE));
    } else {
      span.setResourceName(call.getMethodDescriptor().getFullMethodName());
    }
    return span;
  }

  public AgentSpan onStatus(final AgentSpan span, final Status status) {
    span.setTag("status.code", status.getCode().name());
    span.setTag("status.description", status.getDescription());
    return span.setError(
        SERVER_ERROR_STATUSES.get(status.getCode().value()), ErrorPriorities.HTTP_SERVER_DECORATOR);
  }

  public AgentSpan onClose(final AgentSpan span, final Status status) {
    if (status.getCause() != null) {
      onError(span, status.getCause());
    }
    return onStatus(span, status);
  }

  @Override
  public AgentSpan onError(AgentSpan span, Throwable throwable) {
    super.onError(span, throwable, ErrorPriorities.HTTP_SERVER_DECORATOR);
    if (throwable instanceof StatusRuntimeException) {
      onStatus(span, ((StatusRuntimeException) throwable).getStatus());
    } else if (throwable instanceof StatusException) {
      onStatus(span, ((StatusException) throwable).getStatus());
    }
    return span;
  }
}
