package datadog.trace.instrumentation.grpc.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.DECORATE;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.GRPC_CLIENT;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.GRPC_MESSAGE;
import static datadog.trace.instrumentation.grpc.client.GrpcInjectAdapter.SETTER;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.Set;

public class TracingClientInterceptor implements ClientInterceptor {

  public static final TracingClientInterceptor INSTANCE = new TracingClientInterceptor();
  private static final Set<String> IGNORED_METHODS = Config.get().getGrpcIgnoredOutboundMethods();

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      final MethodDescriptor<ReqT, RespT> method,
      final CallOptions callOptions,
      final Channel next) {
    if (IGNORED_METHODS.contains(method.getFullMethodName())) {
      return next.newCall(method, callOptions);
    }

    final AgentSpan span = startSpan(GRPC_CLIENT).setMeasured(true);
    span.setResourceName(method.getFullMethodName());
    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);

      final ClientCall<ReqT, RespT> result;
      try {
        // call other interceptors
        result = next.newCall(method, callOptions);
      } catch (final Throwable e) {
        DECORATE.onError(span, e);
        DECORATE.beforeFinish(span);
        span.finish();
        throw e;
      }

      return new TracingClientCall<>(span, result);
    }
  }

  static final class TracingClientCall<ReqT, RespT>
      extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {
    final AgentSpan span;

    TracingClientCall(final AgentSpan span, final ClientCall<ReqT, RespT> delegate) {
      super(delegate);
      this.span = span;
    }

    @Override
    public void start(final Listener<RespT> responseListener, final Metadata headers) {
      propagate().inject(span, headers, SETTER);
      try (final AgentScope scope = activateSpan(span)) {
        super.start(new TracingClientCallListener<>(span, responseListener), headers);
      } catch (final Throwable e) {
        DECORATE.onError(span, e);
        DECORATE.beforeFinish(span);
        span.finish();
        throw e;
      }
    }

    @Override
    public void sendMessage(final ReqT message) {
      try (final AgentScope scope = activateSpan(span)) {
        super.sendMessage(message);
      } catch (Throwable e) {
        DECORATE.onError(span, e);
        DECORATE.beforeFinish(span);
        span.finish();
        throw e;
      }
    }
  }

  static final class TracingClientCallListener<RespT>
      extends ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT> {
    final AgentSpan span;

    TracingClientCallListener(final AgentSpan span, final ClientCall.Listener<RespT> delegate) {
      super(delegate);
      this.span = span;
    }

    @Override
    public void onMessage(final RespT message) {
      final AgentSpan messageSpan =
          startSpan(GRPC_MESSAGE, span.context())
              .setTag("message.type", message.getClass().getName());
      DECORATE.afterStart(messageSpan);
      try (AgentScope scope = activateSpan(messageSpan)) {
        delegate().onMessage(message);
      } catch (final Throwable e) {
        DECORATE.onError(messageSpan, e);
        throw e;
      } finally {
        DECORATE.beforeFinish(messageSpan);
        messageSpan.finish();
      }
    }

    @Override
    public void onClose(final Status status, final Metadata trailers) {
      DECORATE.onClose(span, status);
      // Finishes span.
      try (final AgentScope scope = activateSpan(span)) {
        delegate().onClose(status, trailers);
      } catch (final Throwable e) {
        DECORATE.onError(span, e);
        throw e;
      } finally {
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }

    @Override
    public void onReady() {
      try (final AgentScope scope = activateSpan(span)) {
        delegate().onReady();
      } catch (final Throwable e) {
        DECORATE.onError(span, e);
        DECORATE.beforeFinish(span);
        span.finish();
        throw e;
      }
    }
  }
}
