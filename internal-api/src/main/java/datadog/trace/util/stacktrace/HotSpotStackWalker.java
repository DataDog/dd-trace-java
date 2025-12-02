package datadog.trace.util.stacktrace;

import datadog.environment.JavaVirtualMachine;
import java.util.function.Function;
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
      if (JavaVirtualMachine.isJavaVersion(8) && access != null) {
        access.getStackTraceElement(new Throwable(), 0);
        return true;
      }
    } catch (Throwable localThrowable) {
    }
    return false;
  }

  @Override
  <T> T doGetStack(Function<Stream<StackTraceElement>, T> consumer) {

    Throwable throwable = new Throwable();
    Iterable<StackTraceElement> iterable = () -> new HotSpotStackTraceIterator(throwable, access);
    return consumer.apply(StreamSupport.stream(iterable.spliterator(), false));
  }
}
