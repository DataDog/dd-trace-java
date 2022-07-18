package datadog.trace.util.stacktrace;

import datadog.trace.api.Platform;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class HotSpotStackWalker extends AbstractStackWalker {

  private static sun.misc.JavaLangAccess access;

  private static sun.misc.JavaLangAccess get() {
    return sun.misc.SharedSecrets.getJavaLangAccess();
  }

  static {
    try {
      access = get();
    } catch (Throwable e) {
    }
  }

  @Override
  public boolean isEnabled() {
    try {
      if (Platform.isJavaVersion(8) && access != null) {
        access.getStackTraceElement(new Throwable(), 0);
        return true;
      }
    } catch (Throwable localThrowable) {
    }
    return false;
  }

  @Override
  Stream<StackTraceElement> doGetStack() {

    Throwable throwable = new Throwable();

    Iterable<StackTraceElement> iterable =
        () ->
            new Iterator<StackTraceElement>() {

              int index = 0;

              final int size = access.getStackTraceDepth(throwable);

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
            };

    return StreamSupport.stream(iterable.spliterator(), false);
  }
}
