package com.datadog.mlt.sampler;

import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class JMXSampler {
  private final ThreadScopeMapper threadScopeMapper;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread t = new Thread(runnable, "dd-profiling-sampler");
            t.setDaemon(true);
            t.setContextClassLoader(null);
            return t;
          });
  private final AtomicReference<long[]> threadIds = new AtomicReference<>();
  private boolean providerFirstAccess = true;
  private long exceptionCountBeforeLog;
  private long exceptionCount;

  public JMXSampler(ThreadScopeMapper threadScopeMapper) {
    this.threadScopeMapper = threadScopeMapper;
    // TODO period as parameter
    long samplerPeriod = Long.getLong("mlt.sampler.ms", 10);
    // rate limiting exception logging to 1 per minute
    exceptionCountBeforeLog = samplerPeriod != 0 ? 60 * 1000 / samplerPeriod : 60 * 1000;
    executor.scheduleAtFixedRate(this::sample, 0, samplerPeriod, TimeUnit.MILLISECONDS);
  }

  public void shutdown() {
    executor.shutdown();
  }

  /**
   * Adds a thread id to be sampled by the sampler thread. if already present do nothing.
   *
   * @param threadId
   */
  public void addThreadId(long threadId) {
    long[] tmpArray;
    long[] prev = threadIds.get();
    while (prev == null) {
      tmpArray = new long[] {threadId};
      if (threadIds.compareAndSet(null, tmpArray)) {
        return;
      }
      prev = threadIds.get();
    }
    do {
      prev = threadIds.get();
      int idx = Arrays.binarySearch(prev, threadId);
      // check if already exists
      if (idx >= 0) {
        return;
      }
      idx = -idx - 1;
      tmpArray = Arrays.copyOf(prev, prev.length + 1);
      System.arraycopy(tmpArray, idx, tmpArray, idx + 1, prev.length - idx);
      tmpArray[idx] = threadId;
    } while (!threadIds.compareAndSet(prev, tmpArray));
  }

  public void removeThread(long threadId) {
    long[] prev;
    long[] tmpArray;
    do {
      prev = threadIds.get();
      if (prev == null || prev.length == 0) {
        return;
      }
      int idx = 0;
      int size = prev.length;
      while (idx < size && prev[idx] != threadId) {
        idx++;
      }
      if (idx >= size) {
        // not found
        return;
      }
      tmpArray = new long[prev.length - 1];
      System.arraycopy(prev, 0, tmpArray, 0, idx);
      System.arraycopy(prev, idx + 1, tmpArray, idx, tmpArray.length - idx);
    } while (!threadIds.compareAndSet(prev, tmpArray));
  }

  private void sample() {
    try {
      long[] tmpArray = threadIds.get();
      if (tmpArray == null || tmpArray.length == 0) {
        return;
      }
      ThreadStackProvider provider = ThreadStackAccess.getCurrentThreadStackProvider();
      if (provider instanceof NoneThreadStackProvider && providerFirstAccess) {
        log.warn("ThreadStack provider is no op. It will not provide thread stacks.");
        providerFirstAccess = false;
      }
      final ThreadInfo[] threadInfos = provider.getThreadInfo(tmpArray);
      // dispatch to Scopes
      for (ThreadInfo threadInfo : threadInfos) {
        if (threadInfo == null) {
          continue;
        }
        ScopeManager scopeManager = threadScopeMapper.forThread(threadInfo.getThreadId());
        if (scopeManager == null) {
          continue;
        }
        ScopeStackCollector scopeStackCollector = scopeManager.getCurrentScope();
        if (scopeStackCollector == null) {
          continue;
        }
        scopeStackCollector.collect(threadInfo.getStackTrace());
      }
    } catch (Exception ex) {
      if (exceptionCount % exceptionCountBeforeLog == 0) {
        log.info("Exception thrown during JMX sampling:", ex);
      }
      exceptionCount++;
    }
  }
}
