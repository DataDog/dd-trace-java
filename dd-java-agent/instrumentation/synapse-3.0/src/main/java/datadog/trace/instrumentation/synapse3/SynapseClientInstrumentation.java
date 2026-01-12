package datadog.trace.instrumentation.synapse3;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.synapse3.SynapseClientDecorator.DECORATE;
import static datadog.trace.instrumentation.synapse3.SynapseClientDecorator.SYNAPSE_CONTEXT_KEY;
import static datadog.trace.instrumentation.synapse3.SynapseClientDecorator.SYNAPSE_REQUEST;
import static datadog.trace.instrumentation.synapse3.TargetRequestInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.axis2.context.MessageContext;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.synapse.transport.passthru.TargetContext;

@AutoService(InstrumenterModule.class)
public final class SynapseClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SynapseClientInstrumentation() {
    super("synapse3-client", "synapse3");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.synapse.transport.passthru.TargetHandler";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TargetRequestInjectAdapter", packageName + ".SynapseClientDecorator",
    };
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("requestReady"))
            .and(takesArgument(0, named("org.apache.http.nio.NHttpClientConnection"))),
        getClass().getName() + "$ClientRequestAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("responseReceived"))
            .and(takesArgument(0, named("org.apache.http.nio.NHttpClientConnection"))),
        getClass().getName() + "$ClientResponseAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("closed", "exception", "timeout"))
            .and(takesArgument(0, named("org.apache.http.nio.NHttpClientConnection"))),
        getClass().getName() + "$ClientErrorResponseAdvice");
  }

  public static final class ClientRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope beginRequest(
        @Advice.Argument(0) final NHttpClientConnection connection) {

      // check for parent span propagated by SynapsePassthruInstrumentation
      AgentSpan parentSpan = null;
      MessageContext message = TargetContext.get(connection).getRequestMsgCtx();
      if (null != message) {
        parentSpan = (AgentSpan) message.getPropertyNonReplicable(SYNAPSE_CONTEXT_KEY);
        if (null != parentSpan) {
          message.removePropertyNonReplicable(SYNAPSE_CONTEXT_KEY);
        }
      }

      AgentSpan span;
      if (null != parentSpan) {
        span = startSpan(SYNAPSE_REQUEST, parentSpan.context());
      } else {
        span = startSpan(SYNAPSE_REQUEST);
      }

      DECORATE.afterStart(span);

      Context context = getCurrentContext().with(span);

      // add trace id to client-side request before it gets submitted as an HttpRequest
      defaultPropagator().inject(context, TargetContext.getRequest(connection), SETTER);

      // capture context to be finished by one of the various client response advices
      connection.getContext().setAttribute(SYNAPSE_CONTEXT_KEY, context);

      return context.attach();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void requestSubmitted(
        @Advice.Argument(0) final NHttpClientConnection connection,
        @Advice.Enter final ContextScope scope) {
      // populate span using details from the submitted HttpRequest (resolved URI, etc.)
      AgentSpan span = spanFromContext(scope.context());
      DECORATE.onRequest(span, TargetContext.getRequest(connection).getRequest());
      scope.close();
    }
  }

  public static final class ClientResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope beginResponse(
        @Advice.Argument(0) final NHttpClientConnection connection) {
      // check and remove context so it won't be finished twice
      Context context = (Context) connection.getContext().removeAttribute(SYNAPSE_CONTEXT_KEY);
      if (null != context) {
        return context.attach();
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void responseReceived(
        @Advice.Argument(0) final NHttpClientConnection connection,
        @Advice.Enter final ContextScope scope,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }
      AgentSpan span = spanFromContext(scope.context());
      DECORATE.onResponse(span, connection.getHttpResponse());
      if (null != error) {
        DECORATE.onError(span, error);
      }
      DECORATE.beforeFinish(scope.context());
      scope.close();
      if (span != null) {
        span.finish();
      }
    }
  }

  public static final class ClientErrorResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void errorResponse(
        @Advice.Argument(0) final NHttpClientConnection connection,
        @Advice.Argument(value = 1, optional = true) final Object error) {
      // check and remove context so it won't be finished twice
      Context context = (Context) connection.getContext().removeAttribute(SYNAPSE_CONTEXT_KEY);
      AgentSpan span = spanFromContext(context);
      if (null != span) {
        if (error instanceof Throwable) {
          DECORATE.onError(span, (Throwable) error);
        } else {
          span.setError(true);
        }
        DECORATE.beforeFinish(context);
        span.finish();
      } else {
        DECORATE.beforeFinish(context);
      }
    }
  }
}
