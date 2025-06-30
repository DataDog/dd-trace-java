package datadog.trace.instrumentation.akkahttp.appsec;

import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.MethodHandle;
import java.util.Collections;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import scala.collection.immutable.List;
import scala.collection.mutable.ListBuffer;

public class ScalaListCollector<T> implements Collector<T, ListBuffer<T>, List<T>> {

  private static final Collector INSTANCE_TO_LIST;
  private static final MethodHandle PLUS_EQ;
  private static final MethodHandle PLUS_PLUS_EQ;

  static {
    ClassLoader classLoader = ScalaListCollector.class.getClassLoader();
    if (classLoader == null) {
      classLoader = ClassLoader.getSystemClassLoader();
    }

    MethodHandle plusEq;
    MethodHandle plusPlusEq;
    try {
      plusEq =
          lookup()
              .findVirtual(
                  ListBuffer.class, "$plus$eq", methodType(ListBuffer.class, Object.class));
      Class traversableOnceCls = classLoader.loadClass("scala.collection.TraversableOnce");
      plusPlusEq =
          lookup()
              .findVirtual(
                  ListBuffer.class,
                  "$plus$plus$eq",
                  methodType(ListBuffer.class, traversableOnceCls));
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
      try {
        plusEq =
            lookup()
                .findVirtual(
                    ListBuffer.class, "addOne", methodType(ListBuffer.class, Object.class));
        Class iterableOnceCls = classLoader.loadClass("scala.collection.IterableOnce");
        plusPlusEq =
            lookup()
                .findVirtual(
                    ListBuffer.class, "addAll", methodType(ListBuffer.class, iterableOnceCls));
      } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException ex) {
        throw new RuntimeException(ex);
      }
    }

    PLUS_EQ = plusEq;
    PLUS_PLUS_EQ = plusPlusEq;
    INSTANCE_TO_LIST = new ScalaListCollector();
  }

  public static <T> Collector<T, ?, List<T>> toScalaList() {
    return INSTANCE_TO_LIST;
  }

  private static <T> ListBuffer<T> addOne(ListBuffer<T> list, T object) {
    try {
      return (ListBuffer<T>) PLUS_EQ.invoke(list, object);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private static <T> ListBuffer<T> addAll(ListBuffer<T> list, ListBuffer<T> otherList) {
    try {
      return (ListBuffer<T>) PLUS_PLUS_EQ.invoke(list, otherList);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Supplier<ListBuffer<T>> supplier() {
    return scala.collection.mutable.ListBuffer::new;
  }

  @Override
  public BiConsumer<ListBuffer<T>, T> accumulator() {
    return ScalaListCollector::addOne;
  }

  @Override
  public BinaryOperator<ListBuffer<T>> combiner() {
    return ScalaListCollector::addAll;
  }

  @Override
  public Function<ListBuffer<T>, List<T>> finisher() {
    return scala.collection.mutable.ListBuffer::toList;
  }

  @Override
  public Set<Characteristics> characteristics() {
    return Collections.emptySet();
  }
}
