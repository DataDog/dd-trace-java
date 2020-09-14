package datadog.trace.core.monitor;

import static datadog.trace.core.monitor.Utils.mergeTags;

import com.timgroup.statsd.StatsDClient;

public class Counter {

  private final String name;
  private final String[] tags;
  private final StatsDClient statsd;

  Counter(String name, String[] tags, StatsDClient statsd) {
    this.name = name;
    this.tags = tags;
    this.statsd = statsd;
  }

  Counter(String name, StatsDClient statsd) {
    this.name = name;
    this.tags = new String[0];
    this.statsd = statsd;
  }

  public void increment(int delta) {
    statsd.count(name, delta, tags);
  }

  public void incrementErrorCount(String cause, int delta) {
    statsd.count(name, delta, mergeTags(tags, new String[] {"cause:" + cause.replace(' ', '_')}));
  }
}
