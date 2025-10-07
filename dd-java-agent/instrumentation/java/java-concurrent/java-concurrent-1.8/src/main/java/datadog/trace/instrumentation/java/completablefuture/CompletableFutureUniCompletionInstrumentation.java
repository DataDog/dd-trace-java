package datadog.trace.instrumentation.java.completablefuture;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.FORK_JOIN_TASK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Instrumentation for {@code UniCompletion} in {@code CompletableFuture}.
 *
 * <p>A {@code UniCompletion} or one of its subclasses is what is created in code that use the
 * chaining methods on {@code CompletableFuture}, i.e.:
 *
 * <pre>{@code
 * CompletableFuture f = ...
 * f.thenApplyAsync({ r -> ... })
 * }</pre>
 *
 * The general idea is to capture the current scope at the time of creation of the {@code
 * UniCompletion} and then activate that scope around the processing that happens in the {@code
 * tryFire} method.
 *
 * <p>Since {@code UniCompletion} implements {@link Runnable} and extends {@code ForkJoinTask}, and
 * this instrumentation wants to have full control of the captured state, we exclude {@code
 * UniCompletion} and its subclasses from all wrapping, state capturing and context store field
 * injection except our own, via the {@link
 * datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter}.
 *
 * <p>To complicate things, the `tryFire` method can be executed concurrently by competing threads
 * when the Future it is bound to gets completed, so a new state {@link ConcurrentState} and
 * continuation {@code ConcurrentContinuation}, have been introduced to deal with the benign race
 * taking place that decides which thread actually get to run the user code that was supplied.
 */
@AutoService(InstrumenterModule.class)
public class CompletableFutureUniCompletionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForSingleType,
        Instrumenter.HasMethodAdvice,
        ExcludeFilterProvider {
  static final String JAVA_UTIL_CONCURRENT = "java.util.concurrent";
  static final String COMPLETABLE_FUTURE = JAVA_UTIL_CONCURRENT + ".CompletableFuture";
  static final String UNI_COMPLETION = COMPLETABLE_FUTURE + "$UniCompletion";
  static final String ADVICE_BASE = JAVA_UTIL_CONCURRENT + ".CompletableFutureAdvice$";

  public CompletableFutureUniCompletionInstrumentation() {
    super("java_completablefuture", "java_concurrent");
  }

  @Override
  public String instrumentedType() {
    return UNI_COMPLETION;
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(UNI_COMPLETION, ConcurrentState.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), ADVICE_BASE + "UniConstructor");
  }

  @Override
  public Map<ExcludeType, ? extends Collection<String>> excludedClasses() {
    if (!isEnabled()) {
      return Collections.emptyMap();
    }
    String[] classes = {
      // This is not a subclass of UniCompletion and doesn't have a dependent CompletableFuture
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
      // This is not a subclass of UniCompletion and doesn't have a dependent CompletableFuture
      // "java.util.concurrent.CompletableFuture$CoCompletion",
      "java.util.concurrent.CompletableFuture$BiApply",
      "java.util.concurrent.CompletableFuture$BiAccept",
      "java.util.concurrent.CompletableFuture$BiRun",
      "java.util.concurrent.CompletableFuture$BiRelay",
      "java.util.concurrent.CompletableFuture$OrApply",
      "java.util.concurrent.CompletableFuture$OrAccept",
      "java.util.concurrent.CompletableFuture$OrRun",
      // This is not a subclass of UniCompletion and doesn't have a dependent CompletableFuture
      // "java.util.concurrent.CompletableFuture$AnyOf",
      // This is not a subclass of UniCompletion and doesn't have a dependent CompletableFuture
      // "java.util.concurrent.CompletableFuture$Signaller",
    };
    List<String> excludedClasses = Arrays.asList(classes);
    EnumMap<ExcludeType, Collection<String>> excludedTypes = new EnumMap<>(ExcludeType.class);
    excludedTypes.put(RUNNABLE, excludedClasses);
    excludedTypes.put(FORK_JOIN_TASK, excludedClasses);
    excludedTypes.put(EXECUTOR, excludedClasses);
    return excludedTypes;
  }
}
