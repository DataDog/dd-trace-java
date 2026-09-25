package datadog.trace.agent.test.scopediag;

import java.util.ArrayList;
import java.util.List;

/** Removes diagnostic and runtime plumbing from captured call stacks. */
final class StackFilter {
  private static final String[] DROP_PREFIXES = {
    "datadog.trace.agent.test.scopediag.",
    "datadog.trace.core.",
    "datadog.trace.bootstrap.instrumentation.java.concurrent.",
    "datadog.trace.bootstrap.instrumentation.api.",
    "java.util.concurrent.Executors$",
    "jdk.internal.reflect.",
    "java.lang.reflect.",
    "sun.reflect.",
    "org.spockframework.mock.",
    "org.codehaus.groovy.",
    "groovy.lang.",
    "net.bytebuddy.",
  };

  private static final String[] DROP_CLASSES = {
    "datadog.trace.bootstrap.InstrumentationContext",
    "java.util.concurrent.ThreadPoolExecutor",
    "java.util.concurrent.ScheduledThreadPoolExecutor",
    "java.util.concurrent.ForkJoinPool",
    "java.util.concurrent.ForkJoinWorkerThread",
    "java.util.concurrent.FutureTask",
    "java.util.concurrent.CompletableFuture",
  };

  private final int maxFrames;

  StackFilter(int maxFrames) {
    this.maxFrames = maxFrames;
  }

  int maxFrames() {
    return maxFrames;
  }

  StackTraceElement[] filter(StackTraceElement[] raw) {
    if (raw == null) {
      return new StackTraceElement[0];
    }
    List<StackTraceElement> kept = new ArrayList<>(maxFrames);
    for (StackTraceElement frame : raw) {
      if (isDropped(frame)) {
        continue;
      }
      kept.add(frame);
      if (kept.size() >= maxFrames) {
        break;
      }
    }
    return kept.toArray(new StackTraceElement[0]);
  }

  private static boolean isDropped(StackTraceElement frame) {
    String className = frame.getClassName();
    if (className.equals("java.lang.Thread") && frame.getMethodName().equals("getStackTrace")) {
      return true;
    }
    for (String prefix : DROP_PREFIXES) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    for (String droppedClass : DROP_CLASSES) {
      if (className.equals(droppedClass)
          || (className.startsWith(droppedClass)
              && className.charAt(droppedClass.length()) == '$')) {
        return true;
      }
    }
    return false;
  }
}
