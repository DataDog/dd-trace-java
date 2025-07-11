package datadog.trace.instrumentation.armeria.grpc.client;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.GenericClassValue;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.DataStreamsTagsBuilder;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.BitSet;
import java.util.Set;
import java.util.function.Function;

public class GrpcClientDecorator extends ClientDecorator {
  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().client().operationForProtocol("grpc"));
  public static final CharSequence COMPONENT_NAME = UTF8BytesString.create("armeria-grpc-client");
  public static final CharSequence GRPC_MESSAGE = UTF8BytesString.create("grpc.message");

  private static DataStreamsContext createDsmContext() {
    return DataStreamsContext.fromTags(
        new DataStreamsTagsBuilder()
            .withDirection(DataStreamsTags.Direction.Outbound)
            .withType("grpc")
            .build());
  }

  public static final GrpcClientDecorator DECORATE = new GrpcClientDecorator();

  private static final Set<String> IGNORED_METHODS = Config.get().getGrpcIgnoredOutboundMethods();
  private static final BitSet CLIENT_ERROR_STATUSES = Config.get().getGrpcClientErrorStatuses();

  private static final ClassValue<UTF8BytesString> MESSAGE_TYPES =
      GenericClassValue.of(
          // Uses inner class for predictable name for Instrumenter.Default.helperClassNames()
          new Function<Class<?>, UTF8BytesString>() {
            @Override
            public UTF8BytesString apply(Class<?> input) {
              return UTF8BytesString.create(input.getName());
            }
          });

  private static final DDCache<String, String> RPC_SERVICE_CACHE = DDCaches.newFixedSizeCache(64);

  public UTF8BytesString requestMessageType(MethodDescriptor<?, ?> method) {
    return messageType(method.getRequestMarshaller());
  }

  public UTF8BytesString responseMessageType(MethodDescriptor<?, ?> method) {
    return messageType(method.getResponseMarshaller());
  }

  private UTF8BytesString messageType(MethodDescriptor.Marshaller<?> marshaller) {
    return marshaller instanceof MethodDescriptor.ReflectableMarshaller
        ? MESSAGE_TYPES.get(
            ((MethodDescriptor.ReflectableMarshaller<?>) marshaller).getMessageClass())
        : null;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"armeria-grpc-client", "armeria-grpc", "armeria", "grpc-client", "grpc"};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.RPC;
  }

  @Override
  protected String service() {
    return null;
  }

  public <ReqT, RespT> AgentSpan startCall(MethodDescriptor<ReqT, RespT> method) {
    if (IGNORED_METHODS.contains(method.getFullMethodName())) {
      return null;
    }
    AgentSpan span =
        startSpan("grpc", OPERATION_NAME)
            .setTag("request.type", requestMessageType(method))
            .setTag("response.type", responseMessageType(method))
            // method.getServiceName() may not be available on some grpc versions
            .setTag(
                Tags.RPC_SERVICE,
                RPC_SERVICE_CACHE.computeIfAbsent(
                    method.getFullMethodName(), MethodDescriptor::extractFullServiceName));
    span.setResourceName(method.getFullMethodName());
    return afterStart(span);
  }

  public <C> void injectContext(Context context, final C request, CarrierSetter<C> setter) {
    if (traceConfig().isDataStreamsEnabled()) {
      context = context.with(createDsmContext());
    }
    defaultPropagator().inject(context, request, setter);
  }

  public AgentSpan onClose(final AgentSpan span, final Status status) {

    span.setTag("status.code", status.getCode().name());
    span.setTag("status.description", status.getDescription());

    // TODO why is there a mismatch between client / server for calling the onError method?
    onError(span, status.getCause());
    span.setError(CLIENT_ERROR_STATUSES.get(status.getCode().value()));
    return span;
  }
}
