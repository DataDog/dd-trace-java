package datadog.trace.instrumentation.jakarta3;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jakarta3.JakartaRsAnnotationsDecorator.DECORATE;
import static datadog.trace.instrumentation.jakarta3.JakartaRsAnnotationsDecorator.JAKARTA_RS_REQUEST_ABORT;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Default context instrumentation.
 *
 * <p>Jakarta-RS does not define a way to get the matched resource method from the <code>
 * ContainerRequestContext</code>
 *
 * <p>This default instrumentation uses the class name of the filter to create the span. More
 * specific instrumentations may override this value.
 */
@AutoService(Instrumenter.class)
public class DefaultRequestContextInstrumentation extends AbstractRequestContextInstrumentation {
  public static class ContainerRequestContextAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope createGenericSpan(@Advice.This final ContainerRequestContext context) {

      if (context.getProperty(JakartaRsAnnotationsDecorator.ABORT_HANDLED) == null) {
        final AgentSpan parent = activeSpan();
        final AgentSpan span = startSpan(JAKARTA_RS_REQUEST_ABORT);

        // Save spans so a more specific instrumentation can run later
        context.setProperty(JakartaRsAnnotationsDecorator.ABORT_PARENT, parent);
        context.setProperty(JakartaRsAnnotationsDecorator.ABORT_SPAN, span);

        final Class filterClass =
            (Class) context.getProperty(JakartaRsAnnotationsDecorator.ABORT_FILTER_CLASS);
        Method method = null;
        try {
          method = filterClass.getMethod("filter", ContainerRequestContext.class);
        } catch (final NoSuchMethodException e) {
          // Unable to find the filter method.  This should not be reachable because the context
          // can only be aborted inside the filter method
        }

        final AgentScope scope = activateSpan(span);
        scope.setAsyncPropagation(true);

        DECORATE.afterStart(span);
        DECORATE.onJakartaRsSpan(span, parent, filterClass, method, null);

        return scope;
      }

      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      final AgentSpan span = scope.span();
      if (throwable != null) {
        DECORATE.onError(span, throwable);
      }

      DECORATE.beforeFinish(span);
      span.finish();
      scope.close();
    }
  }
}
