package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.dtos.intake.IntakeBatch;
import com.datadog.appsec.report.raw.events.attack.Attack010;
import com.datadog.appsec.util.StandardizedLogging;
import datadog.trace.util.AgentTaskScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReportServiceImpl implements ReportService {

  private static final Logger log = LoggerFactory.getLogger(ReportServiceImpl.class);

  private final AppSecApi api;
  private final ReportStrategy strategy;
  private final TaskScheduler taskScheduler;
  private List<Object> events = new ArrayList<>();
  private AgentTaskScheduler.Scheduled<ReportServiceImpl> scheduledTask;

  /* workaround the finality of AgentTaskScheduler so we can mock it */
  public interface TaskScheduler {
    <T> AgentTaskScheduler.Scheduled<T> scheduleAtFixedRate(
        final AgentTaskScheduler.Task<T> task,
        final T target,
        final long initialDelay,
        final long period,
        final TimeUnit unit);

    static TaskScheduler of(AgentTaskScheduler scheduler) {
      return new TaskSchedulerImpl(scheduler);
    }
  }

  public static class TaskSchedulerImpl implements TaskScheduler {
    private final AgentTaskScheduler scheduler;

    private TaskSchedulerImpl(AgentTaskScheduler scheduler) {
      this.scheduler = scheduler;
    }

    @Override
    public <T> AgentTaskScheduler.Scheduled<T> scheduleAtFixedRate(
        AgentTaskScheduler.Task<T> task, T target, long initialDelay, long period, TimeUnit unit) {
      return this.scheduler.scheduleAtFixedRate(task, target, initialDelay, period, unit);
    }
  }

  public ReportServiceImpl(AppSecApi api, ReportStrategy strategy, TaskScheduler taskScheduler) {
    this.api = api;
    this.strategy = strategy;
    this.taskScheduler = taskScheduler;
  }

  @Override
  public void reportAttack(Attack010 attack) {
    StandardizedLogging.attackQueued(log);
    synchronized (this) {
      lazyStartTask();
      events.add(attack);
    }
    if (strategy.shouldFlush(attack)) {
      flush();
    }
  }

  private void flush() {
    List<Object> oldEvents;
    synchronized (this) {
      oldEvents = events;
      if (oldEvents.size() == 0) {
        return;
      }
      events = new ArrayList<>();
    }
    StandardizedLogging.sendingAttackBatch(log, oldEvents.size());

    IntakeBatch batch =
        new IntakeBatch.IntakeBatchBuilder().withProtocolVersion(1).withEvents(oldEvents).build();

    this.api.sendIntakeBatch(batch, ReportSerializer.getIntakeBatchAdapter());
  }

  private void lazyStartTask() {
    if (scheduledTask != null) {
      return;
    }

    scheduledTask =
        taskScheduler.scheduleAtFixedRate(
            PeriodicFlush.INSTANCE, this, 5 /* initial delay */, 30 /* period */, TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    synchronized (this) {
      if (scheduledTask != null) {
        scheduledTask.cancel();
        scheduledTask = null;
      }
    }
  }

  private static class PeriodicFlush implements AgentTaskScheduler.Task<ReportServiceImpl> {
    private static final AgentTaskScheduler.Task<ReportServiceImpl> INSTANCE = new PeriodicFlush();

    @Override
    public void run(ReportServiceImpl target) {
      if (target.strategy.shouldFlush()) {
        target.flush();
      }
    }
  }
}
