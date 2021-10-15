package datadog.trace.instrumentation.grpc.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.grpc.server.GrpcExtractAdapter.GETTER;
import static datadog.trace.instrumentation.grpc.server.GrpcServerDecorator.DECORATE;
import static datadog.trace.instrumentation.grpc.server.GrpcServerDecorator.GRPC_MESSAGE;
import static datadog.trace.instrumentation.grpc.server.GrpcServerDecorator.GRPC_SERVER;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.concurrent.CancellationException;
import java.util.Set;

public class TracingServerInterceptor implements ServerInterceptor {

  public static final TracingServerInterceptor INSTANCE = new TracingServerInterceptor();
  private static final Set<String> IGNORED_METHODS = Config.get().getGrpcIgnoredInboundMethods();
  
  private TracingServerInterceptor() {}

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      final ServerCall<ReqT, RespT> call,
      final Metadata headers,
      final ServerCallHandler<ReqT, RespT> next) {
    if (IGNORED_METHODS.contains(call.getMethodDescriptor().getFullMethodName())) {
      return next.startCall(call, headers);
    }

    final Context spanContext = propagate().extract(headers, GETTER);
    final AgentSpan span = startSpan(GRPC_SERVER, spanContext).setMeasured(true);
    DECORATE.afterStart(span);
    DECORATE.onCall(span, call);

    final ServerCall.Listener<ReqT> result;
    try (AgentScope scope = activateSpan(span)) {
      // Wrap the server call so that we can decorate the span
      // with the resulting status
      final TracingServerCall<ReqT, RespT> tracingServerCall = new TracingServerCall<>(span, call);
      // call other interceptors
      result = next.startCall(tracingServerCall, headers);
      // the span related work can continue on any thread
      span.startThreadMigration();
    } catch (final Throwable e) {
      if (span.phasedFinish()) {
        DECORATE.onError(span, e);
        DECORATE.beforeFinish(span);
        span.publish();
      }
      throw e;
    }

    // This ensures the server implementation can see the span in scope
    return new TracingServerCallListener<>(span, result);
  }

  static final class TracingServerCall<ReqT, RespT>
      extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {
    final AgentSpan span;

    TracingServerCall(final AgentSpan span, final ServerCall<ReqT, RespT> delegate) {
      super(delegate);
      this.span = span;
    }

    @Override
    public void close(final Status status, final Metadata trailers) {
      DECORATE.onClose(span, status);
      try (final AgentScope scope = activateSpan(span)) {
        // resume the span related work on this thread
        span.finishThreadMigration();
        delegate().close(status, trailers);
      } catch (final Throwable e) {
        DECORATE.onError(span, e);
        throw e;
      } finally {
        if (span.phasedFinish()) {
          DECORATE.beforeFinish(span);
          span.publish();
        }
      }
    }
  }

  static final class TracingServerCallListener<ReqT>
      extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
    private final AgentSpan span;

    TracingServerCallListener(final AgentSpan span, final ServerCall.Listener<ReqT> delegate) {
      super(delegate);
      this.span = span;
    }

    @Override
    public void onMessage(final ReqT message) {
      final AgentSpan msgSpan =
          startSpan(GRPC_MESSAGE, this.span.context())
              .setTag("message.type", message.getClass().getName());
      DECORATE.afterStart(msgSpan);
      try (AgentScope scope = activateSpan(msgSpan)) {
        delegate().onMessage(message);
      } catch (final Throwable e) {
        // I'm not convinced we should actually be finishing the span here...
        if (span.phasedFinish()) {
          DECORATE.onError(msgSpan, e);
          DECORATE.beforeFinish(span);
          span.publish();
        }
        throw e;
      } finally {
        DECORATE.beforeFinish(msgSpan);
        msgSpan.finish();
      }
    }

    @Override
    public void onHalfClose() {
      try (final AgentScope scope = activateSpan(span)) {
        delegate().onHalfClose();
      } catch (final Throwable e) {
        if (span.phasedFinish()) {
          DECORATE.onError(span, e);
          DECORATE.beforeFinish(span);
          span.publish();
        }
        throw e;
      }
    }

    @Override
    public void onCancel() {
      // Finishes span.
      try (final AgentScope scope = activateSpan(span)) {
        delegate().onCancel();
        span.setTag("canceled", true);
      } catch (CancellationException e) {
        // No need to report an exception or mark as error that it was canceled.
        throw e;
      } catch (final Throwable e) {
        DECORATE.onError(span, e);
        throw e;
      } finally {
        if (span.phasedFinish()) {
          DECORATE.beforeFinish(span);
          span.publish();
        }
      }
    }

    @Override
    public void onComplete() {
      // Finishes span.
      try (final AgentScope scope = activateSpan(span)) {
        delegate().onComplete();
      } catch (final Throwable e) {
        DECORATE.onError(span, e);
        throw e;
      } finally {
        /**
         * grpc has quite a few states that can finish the span. rather than track down the correct
         * combination of them to exclusively finish the span, use phasedFinish.
         */
        if (span.phasedFinish()) {
          DECORATE.beforeFinish(span);
          span.publish();
        }
      }
    }

    @Override
    public void onReady() {
      try (final AgentScope scope = activateSpan(span)) {
        delegate().onReady();
      } catch (final Throwable e) {
        if (span.phasedFinish()) {
          DECORATE.onError(span, e);
          DECORATE.beforeFinish(span);
          span.publish();
        }
        throw e;
      }
    }
  }
}
