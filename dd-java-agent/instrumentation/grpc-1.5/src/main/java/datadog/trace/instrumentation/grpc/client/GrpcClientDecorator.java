package datadog.trace.instrumentation.grpc.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.Function;
import datadog.trace.api.GenericClassValue;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.BitSet;
import java.util.Set;

public class GrpcClientDecorator extends ClientDecorator {
  public static final CharSequence GRPC_CLIENT = UTF8BytesString.create("grpc.client");
  public static final CharSequence COMPONENT_NAME = UTF8BytesString.create("grpc-client");
  public static final CharSequence GRPC_MESSAGE = UTF8BytesString.create("grpc.message");
  public static final GrpcClientDecorator DECORATE = new GrpcClientDecorator();

  private static final Set<String> IGNORED_METHODS = Config.get().getGrpcIgnoredOutboundMethods();
  private static final BitSet CLIENT_ERROR_STATUSES = Config.get().getGrpcClientErrorStatuses();

  private static final ClassValue<UTF8BytesString> MESSAGE_TYPES =
      GenericClassValue.of(
          new Function<Class<?>, UTF8BytesString>() {

            @Override
            public UTF8BytesString apply(Class<?> input) {
              return UTF8BytesString.create(input.getName());
            }
          });

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
        startSpan(GRPC_CLIENT)
            .setTag("request.type", requestMessageType(method))
            .setTag("response.type", responseMessageType(method));
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
