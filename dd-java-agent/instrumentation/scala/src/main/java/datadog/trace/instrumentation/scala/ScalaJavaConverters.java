package datadog.trace.instrumentation.scala;

import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ScalaJavaConverters {

  public static <E> Iterable<E> toIterable(@Nonnull final scala.collection.Iterable<E> iterable) {
    return new JavaIterable<>(iterable);
  }

  public static <E> Object[] toArray(@Nullable final scala.collection.Iterable<E> iterable) {
    if (iterable == null) {
      return new Object[0];
    }
    final int size = iterable.size();
    final Object[] array = new Object[size];
    int index = 0;
    for (scala.collection.Iterator<E> iterator = iterable.iterator(); iterator.hasNext(); ) {
      array[index++] = iterator.next();
    }
    return array;
  }

  public static class JavaIterable<E> implements Iterable<E> {

    private final scala.collection.Iterable<E> iterable;

    private JavaIterable(final scala.collection.Iterable<E> iterable) {
      this.iterable = iterable;
    }

    @Override
    @Nonnull
    public Iterator<E> iterator() {
      return new JavaIterator<>(iterable.iterator());
    }
  }

  public static class JavaIterator<E> implements Iterator<E> {

    private final scala.collection.Iterator<E> iterator;

    private JavaIterator(final scala.collection.Iterator<E> iterator) {
      this.iterator = iterator;
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public E next() {
      return iterator.next();
    }
  }
}
