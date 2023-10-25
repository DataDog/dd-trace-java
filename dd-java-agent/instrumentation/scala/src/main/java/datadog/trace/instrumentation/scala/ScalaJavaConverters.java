package datadog.trace.instrumentation.scala;

import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ScalaJavaConverters {

  public static <E> Iterable<E> toIterable(@Nonnull final scala.collection.Iterable<E> iterable) {
    scala.collection.Iterator<E> iterator = iterable.iterator();
    return () ->
        new Iterator<E>() {
          @Override
          public boolean hasNext() {
            return iterator.hasNext();
          }

          @Override
          public E next() {
            return iterator.next();
          }
        };
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
}
