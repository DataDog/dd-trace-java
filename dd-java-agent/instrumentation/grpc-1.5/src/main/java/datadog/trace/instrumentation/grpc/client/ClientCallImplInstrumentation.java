package datadog.trace.instrumentation.grpc.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.CLIENT_PATHWAY_EDGE_TAGS;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.DECORATE;
import static datadog.trace.instrumentation.grpc.client.GrpcClientDecorator.OPERATION_NAME;
import static datadog.trace.instrumentation.grpc.client.GrpcInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.grpc.ClientCall;
import io.grpc.Metadata;
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
    return Collections.singletonMap("io.grpc.ClientCall", AgentScopeContext.class.getName());
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
    public static void capture(@Advice.This io.grpc.ClientCall<?, ?> call) {
      AgentScopeContext context = AgentTracer.activeContext();
      AgentSpan span = context.span();
      // embed the span in the call only if a grpc.client span is active
      if (null != span && OPERATION_NAME.equals(span.getOperationName())) {
        InstrumentationContext.get(ClientCall.class, AgentScopeContext.class).put(call, context);
      }
    }
  }

  public static final class Start {
    @Advice.OnMethodEnter
    public static AgentScope before(
        @Advice.This ClientCall<?, ?> call,
        @Advice.Argument(1) Metadata headers,
        @Advice.Local("$$ddSpan") AgentSpan span) {
      AgentScopeContext context =
          InstrumentationContext.get(ClientCall.class, AgentScopeContext.class).get(call);
      span = context == null ? null : context.span();
      if (null != span) {
        propagate().inject(span, headers, SETTER);
        propagate().injectPathwayContext(context, headers, SETTER, CLIENT_PATHWAY_EDGE_TAGS);
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

  public static final class Cancel {
    @Advice.OnMethodEnter
    public static void before(
        @Advice.This ClientCall<?, ?> call, @Advice.Argument(1) Throwable cause) {
      AgentScopeContext context =
          InstrumentationContext.get(ClientCall.class, AgentScopeContext.class).remove(call);
      AgentSpan span = context == null ? null : context.span();
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
      AgentScopeContext context =
          InstrumentationContext.get(ClientCall.class, AgentScopeContext.class).remove(call);
      AgentSpan span = context == null ? null : context.span();
      if (null != span) {
        DECORATE.onClose(span, status);
        span.finish();
      }
    }
  }
}
