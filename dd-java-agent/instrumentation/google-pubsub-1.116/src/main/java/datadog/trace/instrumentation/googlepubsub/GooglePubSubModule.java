package datadog.trace.instrumentation.googlepubsub;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class GooglePubSubModule extends InstrumenterModule.Tracing
    implements ExcludeFilterProvider {
  public GooglePubSubModule() {
    super("google-pubsub");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PubSubDecorator",
      packageName + ".PubSubDecorator$RegexExtractor",
      packageName + ".TextMapInjectAdapter",
      packageName + ".TextMapExtractAdapter",
      packageName + ".MessageReceiverWrapper",
      packageName + ".MessageReceiverWithAckResponseWrapper",
    };
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    final List<String> gaxInternalRunnables =
        asList(
            "com.google.api.gax.rpc.Watchdog",
            "com.google.api.gax.retrying.BasicRetryingFuture$CompletionListener",
            "com.google.api.gax.retrying.CallbackChainRetryingFuture$AttemptCompletionListener");
    final Map<ExcludeFilter.ExcludeType, Collection<String>> excluded =
        new EnumMap<>(ExcludeFilter.ExcludeType.class);
    excluded.put(RUNNABLE, gaxInternalRunnables);
    excluded.put(EXECUTOR, gaxInternalRunnables);
    return excluded;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new PublisherInstrumentation(),
        new ReceiverInstrumentation(),
        new ReceiverWithAckInstrumentation());
  }
}
