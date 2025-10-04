package datadog.trace.instrumentation.jetty70;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty70.JettyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;

@AutoService(InstrumenterModule.class)
public class ServerHandleInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ServerHandleInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Server";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".JettyDecorator",
      packageName + ".RequestURIDataAdapter",
      "datadog.trace.instrumentation.jetty.JettyBlockResponseFunction",
      "datadog.trace.instrumentation.jetty.JettyBlockingHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("handle")
            .or(named("handleAsync"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.eclipse.jetty.server.HttpConnection"))),
        ServerHandleInstrumentation.class.getName() + "$HandleAdvice");
  }

  static class HandleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static ContextScope onEnter(
        @Advice.Argument(0) HttpConnection connection,
        @Advice.Local("request") Request req,
        @Advice.Local("agentSpan") AgentSpan span) {
      req = connection.getRequest();

      // First check if there's an existing context in the request (from main server span)
      Object existingContext = req.getAttribute(DD_CONTEXT_ATTRIBUTE);

      // see comments in HandleRequestAdvice for jetty-9
      Object dispatchSpan;
      synchronized (req) {
        dispatchSpan = req.getAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
      }
      if (dispatchSpan instanceof AgentSpan) {
        span = (AgentSpan) dispatchSpan;

        // If we have an existing context, create a new context with the dispatch span
        // Otherwise just attach the dispatch span
        if (existingContext instanceof Context) {
          Context contextWithDispatchSpan = ((Context) existingContext).with(span);
          return contextWithDispatchSpan.attach();
        } else {
          return span.attach();
        }
      }

      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(
        @Advice.Enter final ContextScope scope,
        @Advice.Local("request") Request req,
        @Advice.Local("agentSpan") AgentSpan span,
        @Advice.Thrown Throwable t) {
      if (scope == null) {
        return;
      }

      if (t != null) {
        DECORATE.onError(span, t);
      }
      if (!req.getAsyncContinuation().isAsyncStarted()) {
        // finish will be handled by the async listener
        // Use the full context from the scope for beforeFinish
        DECORATE.beforeFinish(scope.context());
        span.finish();
      }
      scope.close();

      synchronized (req) {
        req.removeAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
      }
    }
  }
}
