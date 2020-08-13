package datadog.trace.bootstrap.instrumentation.profiler;

import datadog.common.exec.CommonTaskExecutor;
import datadog.trace.bootstrap.instrumentation.api.Consumer;
import datadog.trace.bootstrap.instrumentation.api.Pair;
import datadog.trace.context.ScopeListener;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class StackSource implements CommonTaskExecutor.Task<StackSource> {
  public static final ScopeListener LISTENER =
      new ScopeListener() {
        @Override
        public void afterScopeActivated() {
          final long id = Thread.currentThread().getId();
          INSTANCE.trackThread(id);
        }

        @Override
        public void afterScopeClosed() {}
      };
  public static final StackSource INSTANCE = new StackSource();

  private static final int DEPTH = 50; // keep relatively small to focus on top of stack.
  private static final Long[] INTERMEDIATE_TYPE = new Long[] {};

  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
  private final List<Consumer<Set<Pair<String, String>>>> consumers = new CopyOnWriteArrayList<>();
  private final Set<Long> trackedThreadIds =
      Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
  private final Set<Pair<String, String>> identifiedTargets = new HashSet<>();

  private volatile long lastModified = 0;
  private volatile long[] threadIds = new long[] {};

  public StackSource() {
    log.info("Beginning jmx stacktrace collection.");
    CommonTaskExecutor.INSTANCE.scheduleAtFixedRate(
        this, this, 1000, 10, TimeUnit.MILLISECONDS, "StackSource Generator");
    CommonTaskExecutor.INSTANCE.scheduleAtFixedRate(
        new Updater(), this, 100, 10, TimeUnit.SECONDS, "StackSource Updater");
  }

  public void addConsumer(final Consumer<Set<Pair<String, String>>> consumer) {
    consumers.add(consumer);
  }

  public void trackThread(final long threadId) {
    if (trackedThreadIds.add(threadId)) {
      final Long[] obj = trackedThreadIds.toArray(INTERMEDIATE_TYPE);
      final long[] prim = new long[obj.length];
      for (int i = 0; i < obj.length; i++) {
        prim[i] = obj[i];
      }
      threadIds = prim;
    }
  }

  @Override
  public void run(final StackSource target) {
    final long[] threadIds = this.threadIds; // avoid changing mid execution.
    if (threadIds.length == 0) {
      return;
    }
    boolean modified = false;
    final ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds, DEPTH);
    for (int i = 0; i < threadInfos.length; i++) {
      final ThreadInfo info = threadInfos[i];
      if (info == null) {
        // Remove entry but don't rebuild array.
        trackedThreadIds.remove(threadIds[i]);
        continue;
      }
      for (final StackTraceElement frame : info.getStackTrace()) {
        if (skipFrame(frame)) {
          continue;
        }
        final Pair<String, String> key = Pair.of(frame.getClassName(), frame.getMethodName());
        modified |= identifiedTargets.add(key);
      }
    }
    if (modified) {
      log.info("Targets identified: {}", identifiedTargets.size());
      lastModified = System.nanoTime();
    }
  }

  private static boolean skipFrame(final StackTraceElement frame) {
    return frame.isNativeMethod()
        || frame.getClassName().startsWith("java")
        || frame.getClassName().startsWith("jdk")
        || frame.getClassName().startsWith("datadog")
        || frame.getClassName().startsWith("sun");
  }

  private class Updater implements CommonTaskExecutor.Task<StackSource> {
    private long lastUpdated = lastModified;

    @Override
    public void run(final StackSource target) {
      if (lastUpdated < lastModified) {
        lastUpdated = lastModified;
        log.info("Updating {} stacktrace consumer(s).", target.consumers.size());
        for (final Consumer<Set<Pair<String, String>>> consumer : target.consumers) {
          consumer.accept(target.identifiedTargets);
        }
      }
    }
  }
}
