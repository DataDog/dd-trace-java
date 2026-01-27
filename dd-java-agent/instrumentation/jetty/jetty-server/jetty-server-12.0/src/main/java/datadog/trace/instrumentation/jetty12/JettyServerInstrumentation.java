package datadog.trace.instrumentation.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class JettyServerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, ExcludeFilterProvider {

  public JettyServerInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.internal.HttpChannelState";
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
      packageName + ".JettyRunnableWrapper"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("onRequest").and(takesArguments(1)),
        packageName + ".JettyServerAdvice$ContextTrackingAdvice",
        packageName + ".JettyServerAdvice$HandleAdvice");
    transformer.applyAdvice(
        named("recycle").and(takesNoArguments()), packageName + ".JettyServerAdvice$ResetAdvice");
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
            "org.eclipse.jetty.util.thread.TimerScheduler$SimpleTask",
            "org.eclipse.jetty.util.thread.SerializedInvoker$NamedRunnable"));
  }
}
