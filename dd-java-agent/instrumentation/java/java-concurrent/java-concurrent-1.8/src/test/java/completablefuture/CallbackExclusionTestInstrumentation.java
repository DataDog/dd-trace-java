package completablefuture;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.COMPLETABLE_FUTURE_CALLBACK;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

@AutoService(InstrumenterModule.class)
public class CallbackExclusionTestInstrumentation extends InstrumenterModule.Tracing
    implements ExcludeFilterProvider {
  public CallbackExclusionTestInstrumentation() {
    super("java_completablefuture");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return emptyList();
  }

  @Override
  public Map<ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(
        COMPLETABLE_FUTURE_CALLBACK,
        asList(
            "completablefuture.CallbackExclusionTestInstrumentation$Excluded",
            "completablefuture.CallbackExclusionTestInstrumentation$ExcludedBinary"));
  }

  public static class Callback implements Consumer<Object>, BiConsumer<Object, Object>, Runnable {
    public int calls;
    public AgentSpan active;
    public boolean fail;

    @Override
    public void run() {
      calls++;
      active = AgentTracer.activeSpan();
      if (fail) {
        throw new IllegalStateException("callback failure");
      }
    }

    @Override
    public void accept(Object value) {
      run();
    }

    @Override
    public void accept(Object value, Object error) {
      run();
    }
  }

  public static class Unary extends Callback implements Function<Object, Object> {
    @Override
    public Object apply(Object value) {
      run();
      return CompletableFuture.completedFuture(value);
    }
  }

  public static class Binary extends Callback implements BiFunction<Object, Object, Object> {
    @Override
    public Object apply(Object left, Object right) {
      run();
      return left;
    }
  }

  public static class Excluded extends Unary {}

  public static class ExcludedBinary extends Binary {}

  public static class UnlistedBinarySubclass extends ExcludedBinary {}

  public static class UnlistedBinarySibling extends Binary {}

  public static class UnlistedSubclass extends Excluded {}

  public static class UnlistedSibling extends Unary {}

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static CompletableFuture<?> register(
      String operation, CompletableFuture source, CompletableFuture other, Callback callback) {
    switch (operation) {
      case "apply":
        return source.thenApply((Function) callback);
      case "accept":
        return source.thenAccept(callback);
      case "run":
        return source.thenRun(callback);
      case "complete":
        return source.whenComplete(callback);
      case "handle":
        return source.handle((BiFunction) callback);
      case "exception":
        return source.exceptionally((Function) callback);
      case "compose":
        return source.thenCompose((Function) callback);
      case "combine":
        return source.thenCombine(other, (BiFunction) callback);
      case "bothAccept":
        return source.thenAcceptBoth(other, callback);
      case "bothRun":
        return source.runAfterBoth(other, callback);
      case "eitherApply":
        return source.applyToEither(other, (Function) callback);
      case "eitherAccept":
        return source.acceptEither(other, callback);
      case "eitherRun":
        return source.runAfterEither(other, callback);
      default:
        throw new IllegalArgumentException(operation);
    }
  }
}
