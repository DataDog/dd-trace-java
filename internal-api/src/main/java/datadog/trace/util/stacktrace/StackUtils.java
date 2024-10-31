package datadog.trace.util.stacktrace;

import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public abstract class StackUtils {

  public static final String META_STRUCT_KEY = "_dd.stack";

  public static <E extends Throwable> E update(
      final E exception, final Function<StackTraceElement[], StackTraceElement[]> filter) {
    final StackTraceElement[] stack = exception.getStackTrace();
    exception.setStackTrace(filter.apply(stack));
    return exception;
  }

  public static <E extends Throwable> E filter(
      final E exception, final Predicate<StackTraceElement> filter) {
    return update(
        exception, stack -> Arrays.stream(stack).filter(filter).toArray(StackTraceElement[]::new));
  }

  public static <E extends Throwable> E filterFirst(
      final E exception, final Predicate<StackTraceElement> filter) {
    return filter(exception, new OneTimePredicate<>(filter));
  }

  public static <E extends Throwable> E filterDatadog(final E exception) {
    return filter(exception, AbstractStackWalker::isNotDatadogTraceStackElement);
  }

  public static <E extends Throwable> E filterFirstDatadog(final E exception) {
    return filterFirst(exception, AbstractStackWalker::isNotDatadogTraceStackElement);
  }

  public static <E extends Throwable> E filterUntil(
      final E exception, final Predicate<StackTraceElement> trace) {
    return update(
        exception,
        stack -> {
          final StackTraceElement[] source = exception.getStackTrace();
          for (int i = 0; i < source.length; i++) {
            if (trace.test(source[i])) {
              final StackTraceElement[] result = new StackTraceElement[source.length - i - 1];
              System.arraycopy(source, i + 1, result, 0, result.length);
              return result;
            }
          }
          return source;
        });
  }

  public static List<StackTraceFrame> generateUserCodeStackTrace() {
    return generateUserCodeStackTrace(AbstractStackWalker::isNotDatadogTraceStackElement);
  }

  /** Function generates stack trace of the user code (excluding datadog classes) */
  public static List<StackTraceFrame> generateUserCodeStackTrace(
      final Predicate<StackTraceElement> filterPredicate) {
    int stackCapacity = Config.get().getAppSecMaxStackTraceDepth();
    List<StackTraceElement> elements =
        StackWalkerFactory.INSTANCE.walk(
            stream ->
                stream.filter(filterPredicate).limit(stackCapacity).collect(Collectors.toList()));
    return IntStream.range(0, elements.size())
        .mapToObj(idx -> new StackTraceFrame(idx, elements.get(idx)))
        .collect(Collectors.toList());
  }

  public static void addStacktraceEventsToMetaStruct(
      final RequestContext reqCtx, final String productKey, final List<StackTraceEvent> events) {
    final Map<String, List<StackTraceEvent>> stackTraceBatch =
        reqCtx.getOrCreateMetaStructTop(META_STRUCT_KEY, k -> new ConcurrentHashMap<>());
    final List<StackTraceEvent> list =
        stackTraceBatch.computeIfAbsent(
            productKey, k -> Collections.synchronizedList(new ArrayList<>()));
    list.addAll(events);
  }

  private static class OneTimePredicate<T> implements Predicate<T> {

    private final Predicate<T> delegate;
    private boolean filtered;

    private OneTimePredicate(final Predicate<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean test(final T item) {
      if (filtered) {
        return true;
      }
      final boolean test = delegate.test(item);
      if (!test) {
        filtered = true;
      }
      return test;
    }
  }
}
