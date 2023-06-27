package datadog.trace.instrumentation.grpc.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.CLIENT_PATHWAY_EDGE_TAGS;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.DECORATE;
import static datadog.trace.instrumentation.grpc.client.GrpcInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusException;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class ClientCallImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public ClientCallImplInstrumentation() {
    super("grpc", "grpc-client");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("io.grpc.ClientCall", AgentSpan.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "io.grpc.internal.ClientCallImpl";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), getClass().getName() + "$Capture");
    transformation.applyAdvice(named("start").and(isMethod()), getClass().getName() + "$Start");
    transformation.applyAdvice(named("cancel").and(isMethod()), getClass().getName() + "$Cancel");
    transformation.applyAdvice(
        named("request")
            .and(isMethod())
            .and(takesArguments(int.class))
            .or(isMethod().and(named("halfClose").and(takesArguments(0)))),
        getClass().getName() + "$ActivateSpan");
    transformation.applyAdvice(
        named("sendMessage").and(isMethod()), getClass().getName() + "$SendMessage");
    transformation.applyAdvice(
        named("closeObserver").and(takesArguments(3)), getClass().getName() + "$CloseObserver");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GrpcClientDecorator",
      packageName + ".GrpcClientDecorator$1",
      packageName + ".GrpcInjectAdapter"
    };
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
        propagate().inject(span, headers, SETTER);
        propagate().injectPathwayContext(span, headers, SETTER, CLIENT_PATHWAY_EDGE_TAGS);
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
        DECORATE.onClose(span, status);
        span.finish();
      }
    }
  }
}
