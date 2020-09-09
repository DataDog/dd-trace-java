package datadog.trace.api;

// not named BiFunction to ease baselining against JDK8
public interface TwoArgFunction<T, U, V> {
  V apply(T left, U right);

  Function<T, V> curry(U specialisation);
}
