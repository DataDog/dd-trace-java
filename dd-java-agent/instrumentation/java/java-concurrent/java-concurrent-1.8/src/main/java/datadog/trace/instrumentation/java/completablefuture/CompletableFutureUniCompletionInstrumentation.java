package datadog.trace.instrumentation.java.completablefuture;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState;

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
 * <p>The general idea is to capture the current scope at the time of creation of the {@code
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
public class CompletableFutureUniCompletionInstrumentation
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  static final String JAVA_UTIL_CONCURRENT = "java.util.concurrent";
  static final String COMPLETABLE_FUTURE = JAVA_UTIL_CONCURRENT + ".CompletableFuture";
  static final String UNI_COMPLETION = COMPLETABLE_FUTURE + "$UniCompletion";
  static final String ADVICE_BASE = JAVA_UTIL_CONCURRENT + ".CompletableFutureAdvice$";

  @Override
  public String instrumentedType() {
    return UNI_COMPLETION;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), ADVICE_BASE + "UniConstructor");
  }
}
