package datadog.trace.instrumentation.grpc.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.DECORATE;
import static datadog.trace.instrumentation.grpc.client.GrpcInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.bytebuddy.asm.Advice;

public final class ClientCallImplInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.grpc.internal.ClientCallImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Capture");
    transformer.applyAdvice(named("start").and(isMethod()), getClass().getName() + "$Start");
    transformer.applyAdvice(named("cancel").and(isMethod()), getClass().getName() + "$Cancel");
    transformer.applyAdvice(
        named("request")
            .and(isMethod())
            .and(takesArguments(int.class))
            .or(isMethod().and(named("halfClose").and(takesArguments(0)))),
        getClass().getName() + "$ActivateSpan");
    transformer.applyAdvice(
        named("sendMessage").and(isMethod()), getClass().getName() + "$SendMessage");
    transformer.applyAdvice(
        named("closeObserver").and(takesArguments(3)), getClass().getName() + "$CloseObserver");
  }

  public static final class Capture {
    @Advice.OnMethodExit
    public static void capture(
        @Advice.This io.grpc.ClientCall<?, ?> call,
        @Advice.Argument(0) MethodDescriptor<?, ?> method) {
      AgentSpan span = DECORATE.startCall(method);
      if (null != span) {
        InstrumentationContext.get(ClientCall.class, AgentSpan.class).put(call, span);
      }
    }
  }

  public static final class Start {
    @Advice.OnMethodEnter
    public static AgentScope before(
        @Advice.This ClientCall<?, ?> call,
        @Advice.Argument(1) Metadata headers,
        @Advice.Local("$$ddSpan") AgentSpan span) {
      span = InstrumentationContext.get(ClientCall.class, AgentSpan.class).get(call);
      if (null != span) {
        DECORATE.injectContext(span, headers, SETTER);
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter AgentScope scope,
        @Advice.Thrown Throwable error,
        @Advice.Local("$$ddSpan") AgentSpan span)
        throws Throwable {
      if (null != scope) {
        scope.close();
      }
      if (null != error && null != span) {
        DECORATE.onError(span, error);
        DECORATE.beforeFinish(span);
        span.finish();
        throw error;
      }
    }
  }

  public static final class ActivateSpan {
    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.This ClientCall<?, ?> call) {
      AgentSpan span = InstrumentationContext.get(ClientCall.class, AgentSpan.class).get(call);
      if (null != span) {
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }

  public static final class SendMessage {
    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.This ClientCall<?, ?> call) {
      // could create a message span here for the request
      AgentSpan span = InstrumentationContext.get(ClientCall.class, AgentSpan.class).get(call);
      if (span != null) {
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }

  public static final class Cancel {
    @Advice.OnMethodEnter
    public static void before(
        @Advice.This ClientCall<?, ?> call, @Advice.Argument(1) Throwable cause) {
      AgentSpan span = InstrumentationContext.get(ClientCall.class, AgentSpan.class).remove(call);
      if (null != span) {
        final SocketAddress socketAddress =
            call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (socketAddress instanceof InetSocketAddress) {
          DECORATE.onPeerConnection(span, (InetSocketAddress) socketAddress);
        }
        if (cause instanceof StatusException) {
          DECORATE.onClose(span, ((StatusException) cause).getStatus());
        }
        span.finish();
      }
    }
  }

  public static final class CloseObserver {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void closeObserver(
        @Advice.This ClientCall<?, ?> call, @Advice.Argument(1) Status status) {
      AgentSpan span = InstrumentationContext.get(ClientCall.class, AgentSpan.class).remove(call);
      if (null != span) {
        final SocketAddress socketAddress =
            call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (socketAddress instanceof InetSocketAddress) {
          DECORATE.onPeerConnection(span, (InetSocketAddress) socketAddress);
        }
        DECORATE.onClose(span, status);
        span.finish();
      }
    }
  }
}
