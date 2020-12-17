package datadog.trace.instrumentation.quartz;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.quartz.QuartzDecorator.DECORATE;
import static datadog.trace.instrumentation.quartz.QuartzDecorator.SCHEDULED_CALL;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

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
public final class QuartzSchedulingInstrumentation extends Instrumenter.Default {

  public QuartzSchedulingInstrumentation() {
    super("quartz");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("org.quartz.Job"));
  }

  //  is execute and takes
  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("execute").and(takesArgument(0, named("org.quartz.JobExecutionContext"))),
        QuartzSchedulingInstrumentation.class.getName() + "$QuartzSchedulingAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".QuartzDecorator"
    };
  }

  public static class QuartzSchedulingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onStartSpan(@Advice.Argument(0) JobExecutionContext context) {
      //      ignore active span
      final AgentSpan span = startSpan(SCHEDULED_CALL, null);
      DECORATE.afterStart(span);
      DECORATE.onExecute(span, context);
      final AgentScope scope = activateSpan(span);
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExitSpan(
        @Advice.Argument(0) JobExecutionContext context,
        @Advice.Thrown Throwable throwable,
        @Advice.Enter AgentScope scope) {
      DECORATE.beforeFinish(scope.span());
      scope.span().finish();
    }
  }
}
