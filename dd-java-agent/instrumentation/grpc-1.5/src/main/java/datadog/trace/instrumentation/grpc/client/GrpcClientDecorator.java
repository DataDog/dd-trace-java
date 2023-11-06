package datadog.trace.instrumentation.grpc.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;

import datadog.trace.api.Config;
import datadog.trace.api.GenericClassValue;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Function;

public class GrpcClientDecorator extends ClientDecorator {
  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().client().operationForProtocol("grpc"));
  public static final CharSequence COMPONENT_NAME = UTF8BytesString.create("grpc-client");
  public static final CharSequence GRPC_MESSAGE = UTF8BytesString.create("grpc.message");

  private static LinkedHashMap<String, String> createClientPathwaySortedTags() {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    result.put(DIRECTION_TAG, DIRECTION_OUT);
    result.put(TYPE_TAG, "grpc");
    return result;
  }

  public static final LinkedHashMap<String, String> CLIENT_PATHWAY_EDGE_TAGS =
      createClientPathwaySortedTags();

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
    return new String[] {"grpc", "grpc-client"};
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
        startSpan(OPERATION_NAME)
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

  public AgentSpan onClose(final AgentSpan span, final Status status) {

    span.setTag("status.code", status.getCode().name());
    span.setTag("status.description", status.getDescription());

    // TODO why is there a mismatch between client / server for calling the onError method?
    onError(span, status.getCause());
    if (CLIENT_ERROR_STATUSES.get(status.getCode().value())) {
      span.setError(true);
    }

    return span;
  }
}
