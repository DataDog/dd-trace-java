package datadog.trace.agent.test.scopediag;

import java.util.ArrayList;
import java.util.List;

/**
 * Trims a raw stack trace down to the frames that point at <em>where</em> a continuation was
 * captured/activated/resolved: it drops the diagnostic plumbing, the scope-manager internals, and
 * the executor/reflection scaffolding, keeping the top {@code maxFrames} meaningful frames.
 */
final class StackFilter {
  private static final String[] DROP_PREFIXES = {
    // diagnostic harness itself
    "datadog.trace.agent.test.scopediag.",
    // tracer scope/continuation machinery and the capture/activate plumbing it sits behind
    "datadog.trace.core.",
    "datadog.trace.bootstrap.instrumentation.java.concurrent.",
    "datadog.trace.bootstrap.instrumentation.api.",
    "datadog.trace.bootstrap.InstrumentationContext",
    // JDK executor/reflection scaffolding between the caller and the capture
    "java.lang.Thread.getStackTrace",
    "java.util.concurrent.ThreadPoolExecutor",
    "java.util.concurrent.ScheduledThreadPoolExecutor",
    "java.util.concurrent.ForkJoinPool",
    "java.util.concurrent.ForkJoinWorkerThread",
    "java.util.concurrent.Executors$",
    "java.util.concurrent.FutureTask",
    "java.util.concurrent.CompletableFuture",
    "jdk.internal.reflect.",
    "java.lang.reflect.",
    "sun.reflect.",
    // Spock/Groovy/ByteBuddy mock & dynamic-dispatch scaffolding (test harness, not a callsite)
    "org.spockframework.mock.",
    "org.codehaus.groovy.",
    "groovy.lang.",
    "net.bytebuddy.",
  };

  private final int maxFrames;

  StackFilter(int maxFrames) {
    this.maxFrames = maxFrames;
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
    String fqn = frame.getClassName() + "." + frame.getMethodName();
    for (String prefix : DROP_PREFIXES) {
      if (fqn.startsWith(prefix) || frame.getClassName().startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
