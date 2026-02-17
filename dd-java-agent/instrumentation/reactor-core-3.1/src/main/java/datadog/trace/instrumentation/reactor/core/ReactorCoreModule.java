package datadog.trace.instrumentation.reactor.core;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class ReactorCoreModule extends InstrumenterModule.ContextTracking
    implements ExcludeFilterProvider {
  public ReactorCoreModule() {
    super("reactor-core");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ReactorAsyncResultExtension", packageName + ".ContextSpanHelper",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> store = new HashMap<>();
    store.put("org.reactivestreams.Subscriber", Context.class.getName());
    store.put("org.reactivestreams.Publisher", Context.class.getName());
    return store;
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return Collections.singletonMap(
        ExcludeFilter.ExcludeType.RUNNABLE,
        Arrays.asList(
            "reactor.core.publisher.EventLoopProcessor",
            "reactor.core.publisher.EventLoopProcessor$RequestTask",
            "reactor.core.publisher.TopicProcessor$TopicInner",
            "reactor.core.publisher.TopicProcessor$TopicInner$1",
            "reactor.core.publisher.WorkQueueProcessor$WorkQueueInner",
            "reactor.core.publisher.WorkQueueProcessor$WorkQueueInner$1"));
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new BlockingPublisherInstrumentation(),
        new CorePublisherInstrumentation(),
        new CoreSubscriberInstrumentation(),
        new OptimizableOperatorInstrumentation());
  }
}
