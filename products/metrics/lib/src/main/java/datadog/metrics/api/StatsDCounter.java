package datadog.metrics.api;

import static datadog.metrics.api.Utils.mergeTags;

import datadog.metrics.statsd.StatsDClient;

public final class StatsDCounter implements Counter {

  private final String name;
  private final String[] tags;
  private final StatsDClient statsd;

  StatsDCounter(String name, StatsDClient statsd) {
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
