package datadog.trace.instrumentation.scala.concurrent;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class ExecutionContextInstrumentation extends Instrumenter.Default {

  public ExecutionContextInstrumentation() {
    super("scala_concurrent");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("scala.concurrent.ExecutionContext");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return safeHasSuperType(named("scala.concurrent.ExecutionContext"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        named("execute").and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$Execute");
  }

  public static final class Execute {
    @Advice.OnMethodEnter
    public static void execute(@Advice.Argument(0) Runnable task) {
      if (!ExcludeFilter.exclude(RUNNABLE, task)) {
        TraceScope scope = activeScope();
        if (null != scope) {
          InstrumentationContext.get(Runnable.class, State.class)
              .putIfAbsent(task, State.FACTORY)
              .captureAndSetContinuation(scope);
        }
      }
    }
  }
}
