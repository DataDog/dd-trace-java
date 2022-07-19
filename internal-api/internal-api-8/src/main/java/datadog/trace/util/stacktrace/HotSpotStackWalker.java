package datadog.trace.util.stacktrace;

import datadog.trace.api.Platform;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class HotSpotStackWalker extends AbstractStackWalker {

  sun.misc.JavaLangAccess access;

  HotSpotStackWalker() {
    try {
      access = sun.misc.SharedSecrets.getJavaLangAccess();
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

    Iterable<StackTraceElement> iterable =
        () -> new HotSpotStackTraceIterator(new Throwable(), access);

    return StreamSupport.stream(iterable.spliterator(), false);
  }
}
