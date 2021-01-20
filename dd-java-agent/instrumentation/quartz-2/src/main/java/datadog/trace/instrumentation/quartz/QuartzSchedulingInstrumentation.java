package datadog.trace.instrumentation.quartz;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.quartz.QuartzDecorator.DECORATE;
import static datadog.trace.instrumentation.quartz.QuartzDecorator.SCHEDULED_CALL;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.quartz.JobExecutionContext;

@AutoService(Instrumenter.class)
public final class QuartzSchedulingInstrumentation extends Instrumenter.Tracing {

  public QuartzSchedulingInstrumentation() {
    super("quartz");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("org.quartz.Job"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(isPublic())
            .and(named("execute"))
            .and(takesArgument(0, named("org.quartz.JobExecutionContext"))),
        QuartzSchedulingInstrumentation.class.getName() + "$QuartzSchedulingAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".QuartzDecorator"};
  }

  public static class QuartzSchedulingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(@Advice.Argument(0) JobExecutionContext context) {
      //      ignore active span
      final AgentSpan span = startSpan(SCHEDULED_CALL);
      DECORATE.afterStart(span);
      DECORATE.onExecute(span, context);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        final AgentSpan span = scope.span();
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
      }
      final AgentSpan span = scope.span();
      span.finish();
      scope.close();
    }
  }
}
