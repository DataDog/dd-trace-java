package datadog.trace.instrumentation.reactor.core;

import static net.bytebuddy.matcher.ElementMatchers.declaresField;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
public class TaskClassInitDisableInstrumentation extends Instrumenter.Tracing {
  public TaskClassInitDisableInstrumentation() {
    super("reactor-core");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return namedOneOf("reactor.core.scheduler.SchedulerTask", "reactor.core.scheduler.WorkerTask")
        .and(declaresField(named("FINISHED").and(ElementMatchers.<FieldDescription>isStatic())))
        .and(declaresField(named("CANCELLED").and(ElementMatchers.<FieldDescription>isStatic())));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isTypeInitializer(), packageName + ".DisableAsyncAdvice");
  }
}
