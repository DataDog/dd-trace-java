package com.datadog.profiling.context;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.Tracer;
import java.lang.reflect.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StatsDAccessor {
  private static final Logger log = LoggerFactory.getLogger(StatsDAccessor.class);

  private static StatsDClient statsd = null;

  public static StatsDClient getStatsdClient() {
    if (statsd == null) {
      // a possibility of harmless data race when the reflective field access will be used several
      // times
      try {
        Tracer tracer = GlobalTracer.get();
        Field fld = tracer.getClass().getDeclaredField("statsDClient");
        fld.setAccessible(true);
        StatsDClient statsdClient = (StatsDClient) fld.get(tracer);
        log.debug("Set up custom StatsD Client instance {}", statsdClient);
        statsd = statsdClient;
      } catch (Throwable t) {
        if (log.isDebugEnabled()) {
          log.warn("Unable to obtain a StatsD client instance", t);
        } else {
          log.warn("Unable to obtain a StatsD client instance");
        }
        statsd = StatsDClient.NO_OP;
      }
    }
    return statsd;
  }
}
