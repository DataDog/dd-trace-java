package datadog.telemetry;

import java.util.*;

/**
 * This class is extension for LinkedHashMap with fixed max capacity In case of overflow the oldest
 * elements will be pushed out (removed) of the Map
 */
public class LimitedLinkedHashSet<E> extends LinkedHashSet<E> {

  private final int maxCapacity;

  public LimitedLinkedHashSet(int maxCapacity) {
    this.maxCapacity = maxCapacity;
  }

  @Override
  public synchronized boolean add(E e) {
    if (!super.add(e)) {
      return false;
    }

    if (size() > maxCapacity) {
      E first = iterator().next();
      remove(first);
    }
    return true;
  }

  @Override
  public synchronized boolean addAll(Collection<? extends E> c) {
    if (!super.addAll(c)) {
      return false;
    }

    int overExtension = size() - maxCapacity;

    final Iterator<E> it = iterator();
    while (it.hasNext() && overExtension-- > 0) {
      it.next();
      it.remove();
    }
    return true;
  }

  @Override
  public synchronized boolean removeAll(Collection<?> c) {
    return super.removeAll(c);
  }
}
