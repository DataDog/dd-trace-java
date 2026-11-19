package datadog.trace.api.function;

/**
 * A function that may throw a checked exception, typically a method reference such as {@code
 * Connection::getClientInfo}. The exception type is a parameter so that it flows through the
 * caller: a function that throws nothing checked declares {@code RuntimeException}.
 */
public interface ThrowingFunction<T, R, E extends Exception> {
  R apply(T t) throws E;
}
