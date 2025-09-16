import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ScheduledFuture;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
public class SchedulingConfig implements SchedulingConfigurer {
  static class TracingTaskScheduler implements TaskScheduler {
    final ThreadPoolTaskScheduler scheduler;

    TracingTaskScheduler() {
      scheduler = new ThreadPoolTaskScheduler();
      scheduler.initialize();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
      return scheduler.scheduleWithFixedDelay(() -> runnableUnderTrace("parent", task), delay);
    }

    @Override
    public Clock getClock() {
      return Clock.systemUTC();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
      return scheduler.schedule(() -> runnableUnderTrace("parent", task), startTime);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable task, Instant startTime, Duration period) {
      return scheduler.scheduleAtFixedRate(
          () -> runnableUnderTrace("parent", task), startTime, period);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
      return scheduler.scheduleAtFixedRate(() -> runnableUnderTrace("parent", task), period);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable task, Instant startTime, Duration delay) {
      return scheduler.scheduleWithFixedDelay(
          () -> runnableUnderTrace("parent", task), startTime, delay);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable runnable, Trigger trigger) {
      return scheduler.schedule(() -> runnableUnderTrace("parent", runnable), trigger);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable runnable, Date date) {
      return scheduler.schedule(() -> runnableUnderTrace("parent", runnable), date);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable, Date date, long l) {
      return scheduler.scheduleAtFixedRate(() -> runnableUnderTrace("parent", runnable), date, l);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable, long l) {
      return scheduler.scheduleAtFixedRate(() -> runnableUnderTrace("parent", runnable), l);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable runnable, Date date, long l) {
      return scheduler.scheduleWithFixedDelay(
          () -> runnableUnderTrace("parent", runnable), date, l);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable runnable, long l) {
      return scheduler.scheduleWithFixedDelay(() -> runnableUnderTrace("parent", runnable), l);
    }
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar scheduledTaskRegistrar) {
    scheduledTaskRegistrar.setTaskScheduler(new TracingTaskScheduler());
  }
}
