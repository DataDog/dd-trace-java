package datadog.trace.util.stacktrace;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class HotSpotStackTraceIterator implements Iterator<StackTraceElement> {

  private int index = 0;

  private final int size;

  private final Throwable throwable;

  private final sun.misc.JavaLangAccess access;

  public HotSpotStackTraceIterator(
      final Throwable throwable, final sun.misc.JavaLangAccess access) {
    this.throwable = throwable;
    this.access = access;
    size = this.access.getStackTraceDepth(throwable);
  }

  @Override
  public boolean hasNext() {
    return index < size;
  }

  @Override
  public StackTraceElement next() {
    if (index >= size) {
      throw new NoSuchElementException();
    }
    return access.getStackTraceElement(throwable, index++);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }
}
