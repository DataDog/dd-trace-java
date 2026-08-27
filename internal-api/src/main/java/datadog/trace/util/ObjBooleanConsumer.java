package datadog.trace.util;

/**
 * {@code boolean}-context sibling of {@link java.util.function.ObjLongConsumer}/{@link
 * java.util.function.ObjIntConsumer}/{@link java.util.function.ObjDoubleConsumer} -- {@code
 * java.util.function} never shipped one.
 */
@FunctionalInterface
public interface ObjBooleanConsumer<T> {
  void accept(T t, boolean value);
}
