package datadog.trace.core.monitor;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.timgroup.statsd.StatsDClient;
import datadog.trace.core.util.SystemAccess;

public class CPUTimer extends Timer {

  private final StatsDClient statsd;

  private final String name;
  private final String[] tags = getTags();
  private long start;
  private long cpuTime = 0;

  CPUTimer(String name, DDSketch histogram, StatsDClient statsd, long flushAfterNanos) {
    super(name, histogram, getTags(), statsd, flushAfterNanos);
    this.name = name + ".cpu";
    this.statsd = statsd;
  }

  @Override
  public Recording start() {
    super.start();
    this.start = SystemAccess.getCurrentThreadCpuTime();
    return this;
  }

  @Override
  public void reset() {
    long cpuNanos = SystemAccess.getCurrentThreadCpuTime();
    if (start > 0) {
      this.cpuTime += (cpuNanos - start);
    }
    this.start = cpuNanos;
    super.reset();
  }

  @Override
  public void stop() {
    if (start > 0) {
      this.cpuTime += SystemAccess.getCurrentThreadCpuTime() - start;
    }
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
