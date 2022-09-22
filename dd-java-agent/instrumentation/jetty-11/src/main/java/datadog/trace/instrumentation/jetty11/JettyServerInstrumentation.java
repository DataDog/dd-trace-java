package datadog.trace.instrumentation.jetty11;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@AutoService(Instrumenter.class)
public final class JettyServerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType, ExcludeFilterProvider {

  public JettyServerInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.HttpChannel";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".JettyDecorator",
      packageName + ".RequestURIDataAdapter",
      packageName + ".JettyServerAdvice",
      packageName + ".JettyServerAdvice$HandleAdvice",
      packageName + ".JettyServerAdvice$ResetAdvice",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        takesNoArguments()
            .and(
                named("handle")
                    .or(
                        // In 9.0.3 the handle logic was extracted out to "handle"
                        // but we still want to instrument run in case handle is missing
                        // (without the risk of double instrumenting).
                        named("run").and(isDeclaredBy(not(declaresMethod(named("handle"))))))),
        packageName + ".JettyServerAdvice$HandleAdvice");
    transformation.applyAdvice(
        // name changed to recycle in 9.3.0
        namedOneOf("reset", "recycle").and(takesNoArguments()),
        packageName + ".JettyServerAdvice$ResetAdvice");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return Collections.singletonMap(
        RUNNABLE,
        Arrays.asList(
            "org.eclipse.jetty.util.thread.strategy.ProduceConsume",
            "org.eclipse.jetty.util.thread.strategy.ExecuteProduceConsume",
            "org.eclipse.jetty.io.ManagedSelector",
            "org.eclipse.jetty.util.thread.TimerScheduler",
            "org.eclipse.jetty.util.thread.TimerScheduler$SimpleTask"));
  }
}
