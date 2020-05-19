package com.datadog.profiling.mlt;

import datadog.trace.core.util.NoneThreadStackProvider;
import datadog.trace.core.util.ThreadStackAccess;
import datadog.trace.core.util.ThreadStackProvider;
import datadog.trace.profiling.Session;
import datadog.trace.profiling.SessionFactory;
import java.lang.management.ThreadInfo;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JMXSessionFactory implements SessionFactory {
  private final StackTraceSink sink;
  private final ThreadStackProvider provider;
  private final Map<Long, JMXSession> jmxSessions = new ConcurrentHashMap<>();
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
  private long samplingCount;

  public JMXSessionFactory(StackTraceSink sink) {
    this.sink = sink;
    provider = ThreadStackAccess.getCurrentThreadStackProvider();
    if (provider instanceof NoneThreadStackProvider) {
      log.warn("ThreadStack provider is oo op. It will not provide thread stacks.");
    }
    start();
  }

  public Session createSession(String id, Thread thread) {
    long threadId = thread.getId();
    JMXSession session =
        jmxSessions.computeIfAbsent(threadId, key -> createNewSession(id, threadId));
    session.incRefCount();
    return session;
  }

  private JMXSession createNewSession(String id, long threadId) {
    return new JMXSession(id, threadId, jmxSessions);
  }

  private void start() {
    // TODO period as parameter
    executor.scheduleAtFixedRate(this::sample, 0, 10, TimeUnit.MILLISECONDS);
  }

  private void sample() {
    int size = jmxSessions.size();
    if (size == 0) {
      return;
    }
    long[] threadIds = new long[size];
    String[] ids = new String[size];
    int i = 0;
    for (Map.Entry<Long, JMXSession> entry : jmxSessions.entrySet()) {
      threadIds[i] = entry.getKey();
      ids[i] = entry.getValue().getId();
      i++;
    }
    ThreadInfo[] threadInfos = provider.getThreadInfo(threadIds);
    sink.write(ids, threadInfos);
    samplingCount++;
    // TODO flushing time as parameter
    if (samplingCount % 100 == 0) {
      byte[] buffer = sink.flush();
      log.info("flushing {} bytes", buffer.length);
    }
  }
}
