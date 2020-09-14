package datadog.trace.core.monitor;

import com.timgroup.statsd.StatsDClient;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

public class CPUTimer extends Timer {

  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
  private final StatsDClient statsd;

  private final String name;
  private final String[] tags = getTags();
  private long start;
  private long cpuTime = 0;

  CPUTimer(String name, StatsDClient statsd, long flushAfterNanos) {
    super(name, getTags(), statsd, flushAfterNanos);
    this.name = name + ".cpu";
    this.statsd = statsd;
  }

  @Override
  public Recording start() {
    super.start();
    this.start = threadMXBean.getCurrentThreadCpuTime();
    return this;
  }

  @Override
  public void reset() {
    long cpuNanos = threadMXBean.getCurrentThreadCpuTime();
    this.cpuTime += (cpuNanos - start);
    this.start = cpuNanos;
    super.reset();
  }

  @Override
  public void stop() {
    this.cpuTime += threadMXBean.getCurrentThreadCpuTime() - start;
    super.stop();
  }

  @Override
  public void flush() {
    super.flush();
    statsd.gauge(name, cpuTime, tags);
    this.cpuTime = 0;
  }

  private static String[] getTags() {
    return new String[] {"thread:" + Thread.currentThread().getName()};
  }
}
