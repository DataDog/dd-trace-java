package datadog.trace.instrumentation.jetty9;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_FIN_DISP_LIST_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty9.JettyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

/**
 * Deals with dispatch spans created in the Servlet instrumentation but that further down the line
 * are not handled by servlets (see AsyncContextInstrumentation).
 *
 * <p>This does not create new dispatch spans. Not all dispatches in jetty involve AsyncContext.
 * There are, for instance, internal error dispatches that won't be detected.
 *
 * <p>One possibility for detecting these is using HttpChannel.Listener's onBeforeDispatch/
 * onDispatchFailure/onAfterDispatch. These are only available starting in 9.4 though.
 */
@AutoService(InstrumenterModule.class)
public class ServerHandleInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ServerHandleInstrumentation() {
    super("jetty");
  }

  @Override
  public String muzzleDirective() {
    return "9_full_series";
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
            .and(takesArgument(0, named("org.eclipse.jetty.server.HttpChannel"))),
        ServerHandleInstrumentation.class.getName() + "$HandleAdvice");
  }

  static class HandleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static ContextScope onEnter(
        @Advice.Argument(0) HttpChannel<?> channel,
        @Advice.Local("agentSpan") AgentSpan span,
        @Advice.Local("request") Request req) {
      req = channel.getRequest();

      // First check if there's an existing context in the request (from main server span)
      Object existingContext = req.getAttribute(DD_CONTEXT_ATTRIBUTE);

      // same logic as in Servlet3Advice. We need to activate/finish the dispatch span here
      // because we don't know if a servlet is going to be called and therefore whether
      // Servlet3Advice will have an opportunity to run.
      // If there is no servlet involved, the span would not be finished.

      Object dispatchSpan;
      synchronized (req) {
        // see AsyncContextInstrumentation on the servlet instrumentation for the creation
        // of this dispatch span.
        // Unfortunately, not all jetty dispatches are detected by the servlet
        // instrumentation, like internal error dispatches
        dispatchSpan = req.getAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
      }
      if (dispatchSpan instanceof AgentSpan) {
        // this is not great, but we leave the attribute. This is because
        // Set{Servlet,Context}PathAdvice
        // looks for this attribute, and we need a way to tell Servlet3Advice not to activate
        // the root span, stored in DD_CONTEXT_ATTRIBUTE.
        // req.removeAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
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

      // Extract the span from the context if we didn't get it from @Local
      if (span == null) {
        span = spanFromContext(scope.context());
      }

      boolean registeredFinishListener;
      synchronized (req) {
        req.removeAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
        // the async finish listener may not have been registered. The listener
        // is not registered upon dispatch to avoid ConcurrentModificationException
        // (see AsyncContextInstrumentation), only when the dispatch is handled
        // (see Servlet3Advice).
        // However, it's possible that the dispatch never passes through servlets,
        // so the listener is never registered. In that case, we handle the dispatch
        // finish here (the reason we cannot handle dispatch finish solely here is
        // because we can't detect async timeouts here)
        registeredFinishListener = req.getAttribute(DD_FIN_DISP_LIST_SPAN_ATTRIBUTE) != null;
      }

      if (t != null) {
        DECORATE.onError(span, t);
      }
      if (!(req.isAsyncStarted() && registeredFinishListener)) {
        // finish will be handled by the async listener
        // Use the full context from the scope for beforeFinish
        DECORATE.beforeFinish(scope.context());
        span.finish();
      }
      scope.close();
    }
  }
}
