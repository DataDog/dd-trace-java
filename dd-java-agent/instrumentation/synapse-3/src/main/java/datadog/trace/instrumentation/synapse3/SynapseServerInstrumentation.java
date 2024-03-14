package datadog.trace.instrumentation.synapse3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.synapse3.SynapseServerDecorator.DECORATE;
import static datadog.trace.instrumentation.synapse3.SynapseServerDecorator.SYNAPSE_SPAN_KEY;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.http.HttpRequest;
import org.apache.http.nio.NHttpServerConnection;

@AutoService(InstrumenterModule.class)
public final class SynapseServerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public SynapseServerInstrumentation() {
    super("synapse3-server", "synapse3");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.synapse.transport.passthru.SourceHandler";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".SynapseServerDecorator",
    };
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("requestReceived"))
            .and(takesArgument(0, named("org.apache.http.nio.NHttpServerConnection"))),
        getClass().getName() + "$ServerRequestAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("responseReady"))
            .and(takesArgument(0, named("org.apache.http.nio.NHttpServerConnection"))),
        getClass().getName() + "$ServerResponseAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("closed", "exception", "timeout"))
            .and(takesArgument(0, named("org.apache.http.nio.NHttpServerConnection"))),
        getClass().getName() + "$ServerErrorResponseAdvice");
  }

  public static final class ServerRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginRequest(
        @Advice.Argument(0) final NHttpServerConnection connection) {

      // check incoming request for distributed trace ids
      HttpRequest request = connection.getHttpRequest();
      AgentSpan.Context.Extracted extractedContext = DECORATE.extract(request);

      AgentSpan span;
      if (null != extractedContext) {
        span = DECORATE.startSpan(request, extractedContext);
      } else {
        span = startSpan(DECORATE.spanName());
        span.setMeasured(true);
      }
      AgentScope scope = activateSpan(span);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, connection, request, extractedContext);

      // capture span to be finished by one of the various server response advices
      connection.getContext().setAttribute(SYNAPSE_SPAN_KEY, span);

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void requestReceived(@Advice.Enter final AgentScope scope) {
      scope.close();
    }
  }

  public static final class ServerResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginResponse(
        @Advice.Argument(0) final NHttpServerConnection connection) {
      // check and remove span from context so it won't be finished twice
      AgentSpan span = (AgentSpan) connection.getContext().removeAttribute(SYNAPSE_SPAN_KEY);
      if (null != span) {
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void responseReady(
        @Advice.Argument(0) final NHttpServerConnection connection,
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }
      AgentSpan span = scope.span();
      DECORATE.onResponse(span, connection.getHttpResponse());
      if (null != error) {
        DECORATE.onError(span, error);
      }
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }

  public static final class ServerErrorResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void errorResponse(
        @Advice.Argument(0) final NHttpServerConnection connection,
        @Advice.Argument(value = 1, optional = true) final Object error) {
      // check and remove span from context so it won't be finished twice
      AgentSpan span = (AgentSpan) connection.getContext().removeAttribute(SYNAPSE_SPAN_KEY);
      if (null != span) {
        if (error instanceof Throwable) {
          DECORATE.onError(span, (Throwable) error);
        } else {
          span.setError(true);
        }
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }
}
