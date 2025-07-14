package datadog.trace.util.stacktrace;

import datadog.environment.JavaVirtualMachine;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressForbidden
public class StackWalkerFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(StackWalkerFactory.class);

  public static final StackWalker INSTANCE;

  static {
    Stream<StackWalker> stream = Stream.of(hotspot(), jdk9()).map(Supplier::get);
    INSTANCE =
        stream
            .filter(Objects::nonNull)
            .filter(StackWalker::isEnabled)
            .findFirst()
            .orElseGet(defaultStackWalker());
  }

  private static Supplier<StackWalker> defaultStackWalker() {
    return DefaultStackWalker::new;
  }

  private static Supplier<StackWalker> hotspot() {
    return () -> {
      if (!JavaVirtualMachine.isJavaVersion(8)) {
        return null;
      }
      return new HotSpotStackWalker();
    };
  }

  private static Supplier<StackWalker> jdk9() {
    return () -> {
      if (!JavaVirtualMachine.isJavaVersionAtLeast(9)) {
        return null;
      }
      try {
        return (StackWalker)
            Class.forName("datadog.trace.util.stacktrace.JDK9StackWalker")
                .getDeclaredConstructor()
                .newInstance();
      } catch (Throwable e) {
        LOGGER.warn("JDK9StackWalker not available", e);
        return null;
      }
    };
  }
}
