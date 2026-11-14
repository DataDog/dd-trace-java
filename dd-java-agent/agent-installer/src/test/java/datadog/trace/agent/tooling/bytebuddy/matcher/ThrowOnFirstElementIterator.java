package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.util.Iterator;

/** Iterator reporting a single element, whose retrieval throws a checked exception. */
class ThrowOnFirstElementIterator<T> implements Iterator<T> {

  private int index = 0;

  @Override
  public boolean hasNext() {
    return index++ < 1;
  }

  @Override
  public T next() {
    throw sneakyThrow(new Exception("iteration exception"));
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws E {
    throw (E) throwable;
  }
}
