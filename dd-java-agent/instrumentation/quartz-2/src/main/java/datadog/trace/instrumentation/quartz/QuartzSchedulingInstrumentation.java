package datadog.trace.instrumentation.quartz;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.quartz.QuartzDecorator.DECORATE;
import static datadog.trace.instrumentation.quartz.QuartzDecorator.SCHEDULED_CALL;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.quartz.JobExecutionContext;

@AutoService(InstrumenterModule.class)
public final class QuartzSchedulingInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public QuartzSchedulingInstrumentation() {
    super("quartz");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.quartz.Job";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
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
      // create a new trace for every job
      final AgentSpan span = startSpan(SCHEDULED_CALL, null);
      DECORATE.afterStart(span);
      DECORATE.onExecute(span, context);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
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
