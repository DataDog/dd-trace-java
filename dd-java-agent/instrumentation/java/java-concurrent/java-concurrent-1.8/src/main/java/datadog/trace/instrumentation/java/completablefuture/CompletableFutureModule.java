package datadog.trace.instrumentation.java.completablefuture;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.FORK_JOIN_TASK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.instrumentation.java.completablefuture.AsyncTaskInstrumentation.CLASS_NAMES;
import static datadog.trace.instrumentation.java.completablefuture.CompletableFutureUniCompletionInstrumentation.UNI_COMPLETION;
import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class CompletableFutureModule extends InstrumenterModule.ContextTracking
    implements ExcludeFilterProvider {
  public CompletableFutureModule() {
    super("java_completablefuture", "java_concurrent");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> context = new HashMap<>();
    context.put("java.util.concurrent.ForkJoinTask", State.class.getName());
    context.put(UNI_COMPLETION, ConcurrentState.class.getName());
    return context;
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    EnumMap<ExcludeFilter.ExcludeType, Collection<String>> excluded =
        new EnumMap<>(ExcludeFilter.ExcludeType.class);
    final List<String> excludedForkJoinClass = new ArrayList<>();
    final List<String> excludedRunnableClasses = new ArrayList<>();
    final List<String> excludedExecutorClasses = new ArrayList<>();
    // AsyncTaskInstrumentation
    excludedForkJoinClass.addAll(asList(CLASS_NAMES));
    excludedRunnableClasses.addAll(asList(CLASS_NAMES));
    if (isEnabled()) {
      // CompletableFutureUniCompletionInstrumentation
      final List<String> unicompletionClasses =
          asList(
              // This is not a subclass of UniCompletion and doesn't have a dependent
              // CompletableFuture
              "java.util.concurrent.CompletableFuture$Completion",
              "java.util.concurrent.CompletableFuture$UniCompletion",
              "java.util.concurrent.CompletableFuture$UniApply",
              "java.util.concurrent.CompletableFuture$UniAccept",
              "java.util.concurrent.CompletableFuture$UniRun",
              "java.util.concurrent.CompletableFuture$UniWhenComplete",
              "java.util.concurrent.CompletableFuture$UniHandle",
              "java.util.concurrent.CompletableFuture$UniExceptionally",
              "java.util.concurrent.CompletableFuture$UniComposeExceptionally",
              "java.util.concurrent.CompletableFuture$UniRelay",
              "java.util.concurrent.CompletableFuture$UniCompose",
              "java.util.concurrent.CompletableFuture$BiCompletion",
              // This is not a subclass of UniCompletion and doesn't have a dependent
              // CompletableFuture
              // "java.util.concurrent.CompletableFuture$CoCompletion",
              "java.util.concurrent.CompletableFuture$BiApply",
              "java.util.concurrent.CompletableFuture$BiAccept",
              "java.util.concurrent.CompletableFuture$BiRun",
              "java.util.concurrent.CompletableFuture$BiRelay",
              "java.util.concurrent.CompletableFuture$OrApply",
              "java.util.concurrent.CompletableFuture$OrAccept",
              "java.util.concurrent.CompletableFuture$OrRun"
              // This is not a subclass of UniCompletion and doesn't have a dependent
              // CompletableFuture
              // "java.util.concurrent.CompletableFuture$AnyOf",
              // This is not a subclass of UniCompletion and doesn't have a dependent
              // CompletableFuture
              // "java.util.concurrent.CompletableFuture$Signaller",
              );
      excludedRunnableClasses.addAll(unicompletionClasses);
      excludedExecutorClasses.addAll(unicompletionClasses);
      excludedForkJoinClass.addAll(unicompletionClasses);
    }

    excluded.put(RUNNABLE, excludedRunnableClasses);
    excluded.put(FORK_JOIN_TASK, excludedForkJoinClass);
    excluded.put(EXECUTOR, excludedExecutorClasses);
    return excluded;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new AsyncTaskInstrumentation(),
        new CompletableFutureUniCompletionInstrumentation(),
        new CompletableFutureUniCompletionSubclassInstrumentation());
  }
}
