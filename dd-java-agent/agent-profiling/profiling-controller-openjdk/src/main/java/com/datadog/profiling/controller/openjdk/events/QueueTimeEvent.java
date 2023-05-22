package com.datadog.profiling.controller.openjdk.events;

import datadog.trace.api.profiling.QueueTiming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.QueueTime")
@Label("QueueTime")
@Description("Datadog queueing time event.")
@Category("Datadog")
@StackTrace(false)
public class QueueTimeEvent extends Event implements QueueTiming {

  @Label("Local Root Span Id")
  private long localRootSpanId;

  @Label("Span Id")
  private long spanId;

  @Label("Origin")
  private Thread origin;

  @Label("Task")
  private Class<?> task;

  @Label("Queue")
  private Class<?> queue;

  @Label("Scheduler")
  private Class<?> scheduler;

  public QueueTimeEvent() {
    this.origin = Thread.currentThread();
    AgentSpan activeSpan = AgentTracer.activeSpan();
    if (activeSpan != null) {
      long spanId = activeSpan.getSpanId();
      AgentSpan rootSpan = activeSpan.getLocalRootSpan();
      this.localRootSpanId = rootSpan == null ? spanId : rootSpan.getSpanId();
      this.spanId = spanId;
    }
    begin();
  }

  public void setQueue(Class<?> queue) {
    this.queue = queue;
  }

  @Override
  public void setTask(Class<?> task) {
    this.task = task;
  }

  @Override
  public void setScheduler(Class<?> scheduler) {
    this.scheduler = scheduler;
  }

  @Override
  public void close() {
    end();
    if (shouldCommit()) {
      commit();
    }
  }
}
