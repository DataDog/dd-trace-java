package datadog.trace.instrumentation.armeria.grpc.server;

import static datadog.trace.api.datastreams.DataStreamsContext.fromTags;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.armeria.grpc.server.GrpcExtractAdapter.GETTER;
import static datadog.trace.instrumentation.armeria.grpc.server.GrpcServerDecorator.DECORATE;
import static datadog.trace.instrumentation.armeria.grpc.server.GrpcServerDecorator.GRPC_MESSAGE;
import static datadog.trace.instrumentation.armeria.grpc.server.GrpcServerDecorator.GRPC_SERVER;
import static datadog.trace.instrumentation.armeria.grpc.server.GrpcServerDecorator.SERVER_PATHWAY_EDGE_TAGS;

import datadog.trace.api.Config;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

public class TracingServerInterceptor implements ServerInterceptor {

  public static final TracingServerInterceptor INSTANCE = new TracingServerInterceptor();
  private static final Set<String> IGNORED_METHODS = Config.get().getGrpcIgnoredInboundMethods();

  private TracingServerInterceptor() {}

  protected static AgentTracer.TracerAPI tracer() {
    return AgentTracer.get();
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      final ServerCall<ReqT, RespT> call,
      final Metadata headers,
      final ServerCallHandler<ReqT, RespT> next) {
    if (IGNORED_METHODS.contains(call.getMethodDescriptor().getFullMethodName())) {
      return next.startCall(call, headers);
    }

    AgentSpanContext spanContext = extractContextAndGetSpanContext(headers, GETTER);
    AgentTracer.TracerAPI tracer = tracer();
    spanContext = callIGCallbackRequestStarted(tracer, spanContext);

    CallbackProvider cbp = tracer.getCallbackProvider(RequestContextSlot.APPSEC);
    final AgentSpan span =
        startSpan(DECORATE.instrumentationNames()[0], GRPC_SERVER, spanContext).setMeasured(true);

    AgentTracer.get()
        .getDataStreamsMonitoring()
        .setCheckpoint(span, fromTags(SERVER_PATHWAY_EDGE_TAGS));

    RequestContext reqContext = span.getRequestContext();
    if (reqContext != null) {
      callIGCallbackClientAddress(cbp, reqContext, call);
      callIGCallbackHeaders(cbp, reqContext, headers);
      callIGCallbackGrpcServerMethod(cbp, reqContext, call.getMethodDescriptor());
    }

    DECORATE.afterStart(span);
    DECORATE.onCall(span, call);

    final ServerCall.Listener<ReqT> result;
    try (AgentScope scope = activateSpan(span)) {
      // Wrap the server call so that we can decorate the span
      // with the resulting status
      final TracingServerCall<ReqT, RespT> tracingServerCall = new TracingServerCall<>(span, call);
      // call other interceptors
      result = next.startCall(tracingServerCall, headers);
    } catch (final Throwable e) {
      if (span.phasedFinish()) {
        DECORATE.onError(span, e);
        DECORATE.beforeFinish(span);
        callIGCallbackRequestEnded(span);
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
        delegate().close(status, trailers);
      } catch (final Throwable e) {
        DECORATE.onError(span, e);
        throw e;
      } finally {
        if (span.phasedFinish()) {
          DECORATE.beforeFinish(span);
          callIGCallbackRequestEnded(span);
          span.publish();
        }
      }
    }
  }

  public static final class TracingServerCallListener<ReqT>
      extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
    private final AgentSpan span;

    TracingServerCallListener(final AgentSpan span, final ServerCall.Listener<ReqT> delegate) {
      super(delegate);
      this.span = span;
    }

    @Override
    public void onMessage(final ReqT message) {
      final AgentSpan msgSpan =
          startSpan(DECORATE.instrumentationNames()[0], GRPC_MESSAGE, this.span.context())
              .setTag("message.type", message.getClass().getName());
      DECORATE.afterStart(msgSpan);
      try (AgentScope scope = activateSpan(msgSpan)) {
        callIGCallbackGrpcMessage(msgSpan, message);
        delegate().onMessage(message);
      } catch (final Throwable e) {
        // I'm not convinced we should actually be finishing the span here...
        if (span.phasedFinish()) {
          DECORATE.onError(msgSpan, e);
          DECORATE.beforeFinish(span);
          callIGCallbackRequestEnded(span);
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
          callIGCallbackRequestEnded(span);
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
          callIGCallbackRequestEnded(span);
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
          callIGCallbackRequestEnded(span);
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
          callIGCallbackRequestEnded(span);
          span.publish();
        }
        throw e;
      }
    }
  }

  // IG helpers follow

  private static AgentSpanContext callIGCallbackRequestStarted(
      AgentTracer.TracerAPI cbp, AgentSpanContext context) {
    Supplier<Flow<Object>> startedCbAppSec =
        cbp.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestStarted());
    Supplier<Flow<Object>> startedCbIast =
        cbp.getCallbackProvider(RequestContextSlot.IAST).getCallback(EVENTS.requestStarted());

    if (startedCbAppSec == null && startedCbIast == null) {
      return context;
    }

    TagContext tagContext = null;
    if (context == null) {
      tagContext = new TagContext();
    } else if (context instanceof TagContext) {
      tagContext = (TagContext) context;
    }
    if (tagContext != null) {
      if (startedCbAppSec != null) {
        Flow<Object> flowAppSec = startedCbAppSec.get();
        tagContext.withRequestContextDataAppSec(flowAppSec.getResult());
      }
      if (startedCbIast != null) {
        Flow<Object> flowIast = startedCbIast.get();
        tagContext.withRequestContextDataIast(flowIast.getResult());
      }
      return tagContext;
    }

    return context;
  }

  private static <ReqT, RespT> void callIGCallbackClientAddress(
      CallbackProvider cbp, RequestContext ctx, ServerCall<ReqT, RespT> call) {
    SocketAddress socketAddress = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
    TriFunction<RequestContext, String, Integer, Flow<Void>> cb =
        cbp.getCallback(EVENTS.requestClientSocketAddress());
    if (socketAddress == null || !(socketAddress instanceof InetSocketAddress) || cb == null) {
      return;
    }

    InetSocketAddress inetSockAddr = (InetSocketAddress) socketAddress;
    cb.apply(ctx, inetSockAddr.getHostString(), inetSockAddr.getPort());
  }

  private static void callIGCallbackHeaders(
      CallbackProvider cbp, RequestContext reqCtx, Metadata metadata) {
    TriConsumer<RequestContext, String, String> headerCb = cbp.getCallback(EVENTS.requestHeader());
    Function<RequestContext, Flow<Void>> headerEndCb = cbp.getCallback(EVENTS.requestHeaderDone());
    if (headerCb == null || headerEndCb == null) {
      return;
    }
    for (String key : metadata.keys()) {
      if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX) && !key.startsWith(":")) {
        Metadata.Key<String> mdKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
        for (String value : metadata.getAll(mdKey)) {
          headerCb.accept(reqCtx, key, value);
        }
      }
    }

    headerEndCb.apply(reqCtx);
  }

  private static void callIGCallbackRequestEnded(@Nonnull final AgentSpan span) {
    CallbackProvider cbp = tracer().getUniversalCallbackProvider();
    if (cbp == null) {
      return;
    }
    RequestContext requestContext = span.getRequestContext();
    if (requestContext != null) {
      BiFunction<RequestContext, IGSpanInfo, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestEnded());
      if (callback != null) {
        callback.apply(requestContext, span);
      }
    }
  }

  private static <ReqT, RespT> void callIGCallbackGrpcServerMethod(
      CallbackProvider cbp, RequestContext ctx, MethodDescriptor<ReqT, RespT> methodDescriptor) {
    String method = methodDescriptor.getFullMethodName();
    BiFunction<RequestContext, String, Flow<Void>> cb = cbp.getCallback(EVENTS.grpcServerMethod());
    if (method == null || cb == null) {
      return;
    }
    cb.apply(ctx, method);
  }

  private static void callIGCallbackGrpcMessage(@Nonnull final AgentSpan span, Object obj) {
    if (obj == null) {
      return;
    }

    CallbackProvider cbpAppsec = tracer().getCallbackProvider(RequestContextSlot.APPSEC);
    CallbackProvider cbpIast = tracer().getCallbackProvider(RequestContextSlot.IAST);
    if (cbpAppsec == null && cbpIast == null) {
      return;
    }
    RequestContext requestContext = span.getRequestContext();
    if (requestContext == null) {
      return;
    }

    if (cbpAppsec != null) {
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbpAppsec.getCallback(EVENTS.grpcServerRequestMessage());
      if (callback != null) {
        callback.apply(requestContext, obj);
      }
    }

    if (cbpIast != null) {
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbpIast.getCallback(EVENTS.grpcServerRequestMessage());
      if (callback != null) {
        callback.apply(requestContext, obj);
      }
    }
  }
}
