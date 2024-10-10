package datadog.trace.instrumentation.reactor.core;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class BlockScopePropagationInstrumentation extends InstrumenterModule.Tracing
    implements ExcludeFilterProvider {
  public BlockScopePropagationInstrumentation() {
    super("reactor-core");
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
  public void methodAdvice(MethodTransformer transformer) {}
}
