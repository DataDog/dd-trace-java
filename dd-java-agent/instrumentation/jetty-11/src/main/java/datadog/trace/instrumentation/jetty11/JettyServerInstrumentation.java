package datadog.trace.instrumentation.jetty11;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JettyServerInstrumentation extends Instrumenter.Tracing
    implements ExcludeFilterProvider {

  public JettyServerInstrumentation() {
    super("jetty");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.eclipse.jetty.server.HttpChannel");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JettyDecorator",
      packageName + ".RequestExtractAdapter",
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
                        named("run")
                            .and(
                                new ElementMatcher.Junction.AbstractBase<MethodDescription>() {
                                  @Override
                                  public boolean matches(MethodDescription target) {
                                    // TODO this could probably be made into a nicer matcher.
                                    return !declaresMethod(named("handle"))
                                        .matches(target.getDeclaringType().asErasure());
                                  }
                                }))),
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
