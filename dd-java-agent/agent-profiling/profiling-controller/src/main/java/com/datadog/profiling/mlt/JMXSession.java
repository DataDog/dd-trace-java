package com.datadog.profiling.mlt;

import datadog.trace.api.profiling.Session;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class JMXSession implements Session {
  private final JMXSessionFactory factory;
  private final ThreadStackProvider provider;
  private final Set<Long> threadsIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

  public JMXSession(JMXSessionFactory factory, Thread thread) {
    this.factory = factory;
    this.threadsIds.add(thread.getId());
    provider = getProvider();
    start();
  }

  public void addThread(Thread thread) {
    threadsIds.add(thread.getId());
  }

  public void close() {
    factory.decCount();
  }

  private void start() {
    executor.scheduleAtFixedRate(this::sample, 0, 10, TimeUnit.MILLISECONDS); // TODO period as parameter
  }

  private void sample() {
    List<StackTraceElement[]> stackTraces = new ArrayList<>();
    provider.getStackTrace(threadsIds, stackTraces);
    // TODO write stack traces to JFR file
  }

  private static ThreadStackProvider getProvider() {
    try {
      return (ThreadStackProvider)
        Class.forName("datadog.trace.api.profiling.JmxThreadStackProvider")
          .getField("INSTANCE")
          .get(null);
    } catch (Exception ex) {
      return NoneThreadStackProvider.INSTANCE;
    }
  }
}
