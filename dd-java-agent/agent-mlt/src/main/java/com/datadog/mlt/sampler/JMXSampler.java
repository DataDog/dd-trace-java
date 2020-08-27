package com.datadog.mlt.sampler;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.lang.management.ThreadInfo;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class JMXSampler {
  private static final int SAMPLER_DELAY = Integer.getInteger("mlt.sampler.delay.ms", 25);
  private static final int[] BACKOFF_SAMPLING_INTERVALS = {SAMPLER_DELAY, 1, 2, 5, 10, 50, 100};
  private static final int[] BACKOFF_SAMPLING_COUNT = {1, 20, 25, 20, 100, 2_000, 200};
  private static final ThreadSampleInfo[] EMPTY_INFO = new ThreadSampleInfo[0];

  private final ThreadScopeMapper threadScopeMapper;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread t = new Thread(runnable, "dd-profiling-sampler");
            t.setDaemon(true);
            t.setContextClassLoader(null);
            return t;
          });
  private final ConcurrentMap<Long, ThreadSampleInfo> threadSampleInfoMap =
      new ConcurrentHashMap<>();
  private ScheduledFuture<?> threadDumpFuture;
  private boolean providerFirstAccess = true;
  private long exceptionCountBeforeLog;
  private long exceptionCount;

  public JMXSampler(ThreadScopeMapper threadScopeMapper) {
    this.threadScopeMapper = threadScopeMapper;
    // TODO period as parameter
    long samplerPeriod = Long.getLong("mlt.sampler.ms", 1);
    // rate limiting exception logging to 1 per minute
    exceptionCountBeforeLog = samplerPeriod != 0 ? 60 * 1000 / samplerPeriod : 60 * 1000;
    threadDumpFuture =
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
    threadSampleInfoMap.computeIfAbsent(threadId, ThreadSampleInfo::new);
  }

  public void removeThread(long threadId) {
    threadSampleInfoMap.remove(threadId);
    // threadSampleInfoMap.computeIfPresent(threadId, (key, threadSampleInfo) -> null);
  }

  private void sample() {

    try {
      if (threadSampleInfoMap.isEmpty()) {
        return;
      }
      // snapshot of object references
      ThreadSampleInfo[] threadSampleInfos = threadSampleInfoMap.values().toArray(EMPTY_INFO);
      // prepare thread id array
      LongList threadIdList = new LongArrayList();
      for (ThreadSampleInfo info : threadSampleInfos) {
        if (info.backoffIdx >= BACKOFF_SAMPLING_COUNT.length || info.backoffIdx < 0) {
          continue;
        }
        info.msInterval++;
        if (info.msInterval == BACKOFF_SAMPLING_INTERVALS[info.backoffIdx]) {
          info.msInterval = 0;
          threadIdList.add(info.threadId);
        } else {
          continue;
        }
        info.sampleCount++;
        if (info.sampleCount == BACKOFF_SAMPLING_COUNT[info.backoffIdx]) {
          if (info.backoffIdx + 1 < BACKOFF_SAMPLING_INTERVALS.length) {
            log.debug(
                "Backing off sampling interval for thread[{}] from {}ms to {}ms",
                info.threadId,
                BACKOFF_SAMPLING_INTERVALS[info.backoffIdx],
                BACKOFF_SAMPLING_INTERVALS[info.backoffIdx + 1]);
          }
          info.backoffIdx++;
          info.msInterval = 0;
          info.sampleCount = 0;
        }
      }
      if (threadIdList.isEmpty()) {
        return;
      }
      long[] threadsIds = threadIdList.toLongArray();
      ThreadStackProvider provider = ThreadStackAccess.getCurrentThreadStackProvider();
      if (provider instanceof NoneThreadStackProvider && providerFirstAccess) {
        log.warn("ThreadStack provider is no op. It will not provide thread stacks.");
        providerFirstAccess = false;
      }
      final ThreadInfo[] threadInfos = provider.getThreadInfo(threadsIds);
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

  private static class ThreadSampleInfo {
    final long threadId;
    int backoffIdx;
    int sampleCount;
    int msInterval;

    public ThreadSampleInfo(long threadId) {
      this.threadId = threadId;
    }
  }
}

