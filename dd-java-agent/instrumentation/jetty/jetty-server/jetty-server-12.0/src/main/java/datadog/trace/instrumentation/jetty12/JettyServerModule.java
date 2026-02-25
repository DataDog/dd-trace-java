package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class JettyServerModule extends InstrumenterModule.Tracing
    implements ExcludeFilterProvider {

  public JettyServerModule() {
    super("jetty");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new JettyServerInstrumentation(), new JettyResponseInstrumentation());
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
      packageName + ".JettyRunnableWrapper"
    };
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return Collections.singletonMap(
        RUNNABLE,
        asList(
            "org.eclipse.jetty.util.thread.strategy.ProduceConsume",
            "org.eclipse.jetty.util.thread.strategy.ExecuteProduceConsume",
            "org.eclipse.jetty.io.ManagedSelector",
            "org.eclipse.jetty.util.thread.TimerScheduler",
            "org.eclipse.jetty.util.thread.TimerScheduler$SimpleTask",
            "org.eclipse.jetty.util.thread.SerializedInvoker$NamedRunnable"));
  }
}
