package com.datadog.profiling.mlt;

import datadog.common.exec.DaemonThreadFactory;
import datadog.trace.core.util.NoneThreadStackProvider;
import datadog.trace.core.util.ThreadStackAccess;
import datadog.trace.core.util.ThreadStackProvider;
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
  private final ThreadStackProvider provider;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("dd-profiling-sampler"));
  private final AtomicReference<long[]> threadIds = new AtomicReference<>();

  public JMXSampler(ThreadScopeMapper threadScopeMapper) {
    this.threadScopeMapper = threadScopeMapper;
    provider = ThreadStackAccess.getCurrentThreadStackProvider();
    if (provider instanceof NoneThreadStackProvider) {
      log.warn("ThreadStack provider is no op. It will not provide thread stacks.");
    }
    // TODO period as parameter
    executor.scheduleAtFixedRate(this::sample, 0, 10, TimeUnit.MILLISECONDS);
  }

  public void shutdown() {
    executor.shutdown();
  }

  /**
   * Adds a thread id to be sampled by the sampler thread. /!\ There is no verification of multiple
   * occurrences of this id.
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
      tmpArray = Arrays.copyOf(prev, prev.length + 1);
      tmpArray[tmpArray.length - 1] = threadId;
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
    long[] tmpArray = threadIds.get();
    if (tmpArray == null || tmpArray.length == 0) {
      return;
    }
    ThreadInfo[] threadInfos = provider.getThreadInfo(tmpArray);
    // dispatch to Scopes
    for (ThreadInfo threadInfo : threadInfos) {
      ScopeManager scopeManager = threadScopeMapper.forThread(threadInfo.getThreadId());
      ScopeStackCollector scopeStackCollector = scopeManager.getCurrentScope();
      scopeStackCollector.collect(threadInfo.getStackTrace());
    }
  }
}
