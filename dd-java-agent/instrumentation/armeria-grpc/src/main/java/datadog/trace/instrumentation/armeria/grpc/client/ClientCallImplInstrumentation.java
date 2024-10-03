package datadog.trace.instrumentation.armeria.grpc.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.armeria.grpc.client.GrpcClientDecorator.CLIENT_PATHWAY_EDGE_TAGS;
import static datadog.trace.instrumentation.armeria.grpc.client.GrpcClientDecorator.DECORATE;
import static datadog.trace.instrumentation.armeria.grpc.client.GrpcClientDecorator.GRPC_MESSAGE;
import static datadog.trace.instrumentation.armeria.grpc.client.GrpcClientDecorator.OPERATION_NAME;
import static datadog.trace.instrumentation.armeria.grpc.client.GrpcInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class ClientCallImplInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public ClientCallImplInstrumentation() {
    super("armeria-grpc-client", "armeria-grpc", "armeria", "grpc-client", "grpc");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("io.grpc.ClientCall", AgentSpan.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "com.linecorp.armeria.internal.client.grpc.ArmeriaClientCall";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference(
          new String[0],
          1,
          "com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer",
          null,
          new String[0],
          new Reference.Field[0],
          new Reference.Method[0])
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GrpcClientDecorator",
      packageName + ".GrpcClientDecorator$1",
      packageName + ".GrpcInjectAdapter"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(4, named("io.grpc.MethodDescriptor"))),
        getClass().getName() + "$CaptureCall");
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
        named("close").and(isMethod().and(takesArguments(2))),
        getClass().getName() + "$CloseObserver");
    if (InstrumenterConfig.get()
        .isIntegrationEnabled(Arrays.asList("armeria-grpc-message", "grpc-message"), false)) {
      transformer.applyAdvice(
          named("onNext").or(named("messageRead")), getClass().getName() + "$ReceiveMessages");
    }
  }

  public static final class CaptureCall {
    @Advice.OnMethodExit
    public static void capture(
        @Advice.This ClientCall<?, ?> call, @Advice.Argument(4) MethodDescriptor<?, ?> method) {
      AgentSpan span = DECORATE.startCall(method);
      if (null != span) {
        InstrumentationContext.get(ClientCall.class, AgentSpan.class).put(call, span);
      }
    }
  }

  public static final class Start {
    @Advice.OnMethodEnter
    public static <T> AgentScope before(
        @Advice.This ClientCall<?, ?> call,
        @Advice.Argument(0) ClientCall.Listener<T> responseListener,
        @Advice.Argument(1) Metadata headers,
        @Advice.Local("$$ddSpan") AgentSpan span) {
      if (null != responseListener && null != headers) {
        span = InstrumentationContext.get(ClientCall.class, AgentSpan.class).get(call);
        if (null != span) {
          propagate().inject(span, headers, SETTER);
          propagate().injectPathwayContext(span, headers, SETTER, CLIENT_PATHWAY_EDGE_TAGS);
          return activateSpan(span);
        }
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
    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.This ClientCall<?, ?> call) {
      // could create a message span here for the request
      AgentSpan span = InstrumentationContext.get(ClientCall.class, AgentSpan.class).remove(call);
      if (span != null) {
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void closeObserver(
        @Advice.Enter AgentScope scope, @Advice.Argument(0) Status status) {
      if (null != scope) {
        DECORATE.onClose(scope.span(), status);
        scope.span().finish();
        scope.close();
      }
    }
  }

  public static final class ReceiveMessages {
    @Advice.OnMethodEnter
    public static AgentScope before() {
      AgentSpan clientSpan = activeSpan();
      if (clientSpan != null && OPERATION_NAME.equals(clientSpan.getOperationName())) {
        AgentSpan messageSpan =
            startSpan(GRPC_MESSAGE).setTag("message.type", clientSpan.getTag("response.type"));
        DECORATE.afterStart(messageSpan);
        return activateSpan(messageSpan);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      if (null != scope) {
        scope.span().finish();
        scope.close();
      }
    }
  }
}
