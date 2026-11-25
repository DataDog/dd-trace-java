package datadog.trace.bootstrap.jit;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.TreeSet;

/**
 * Replacement for {@code c.toArray(new T[c.size()])}: for JDK collections whose {@code size()} has
 * no side effects, {@code c.toArray(new T[0])} gives the same array without the separate {@code
 * size()} call and without zeroing an array that is then overwritten.
 *
 * <p>Only exact classes take that path (not subclasses, which may override {@code size()}). Any
 * other collection runs the original expression, in the original order: {@code size()}, then the
 * allocation, then {@code toArray}. If another thread changes the collection between the original's
 * {@code size()} and {@code toArray}, the original can return a longer array ending in {@code
 * null}s; the zero-sized form always returns what the original returns when nothing changes in
 * between, which is one of the original's possible outcomes.
 */
public final class CollectionFusions {
  private CollectionFusions() {}

  private static final Class<?>[] KNOWN = {
    ArrayList.class,
    LinkedList.class,
    ArrayDeque.class,
    HashSet.class,
    LinkedHashSet.class,
    TreeSet.class,
    Arrays.asList().getClass(),
    new HashMap<>().keySet().getClass(),
    new HashMap<>().values().getClass(),
    new HashMap<>().entrySet().getClass(),
    new LinkedHashMap<>().keySet().getClass(),
    new LinkedHashMap<>().values().getClass(),
    new LinkedHashMap<>().entrySet().getClass()
  };

  /**
   * {@code c.toArray(new T[c.size()])}, given a fresh zero-length {@code T[]} from the call site.
   */
  public static Object[] toArrayPresized(Collection<?> c, Object[] zeroLength) {
    Class<?> type = c.getClass(); // a null collection throws like the original's size()
    for (Class<?> known : KNOWN) {
      if (type == known) {
        return c.toArray(zeroLength);
      }
    }
    int size = c.size();
    Object[] presized =
        (Object[]) Array.newInstance(zeroLength.getClass().getComponentType(), size);
    return c.toArray(presized);
  }
}
