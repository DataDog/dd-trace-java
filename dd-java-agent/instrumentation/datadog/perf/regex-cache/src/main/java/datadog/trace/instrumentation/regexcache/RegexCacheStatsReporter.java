package datadog.trace.instrumentation.regexcache;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.bootstrap.regex.RegexCache;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RegexCacheStatsReporter implements AgentTaskScheduler.Task<Logger> {

  private static final long PERIOD_SECONDS = 60;
  private static final AtomicBoolean STARTED = new AtomicBoolean();

  private RegexCacheStatsReporter() {}

  public static void start() {
    if (STARTED.compareAndSet(false, true)) {
      AgentTaskScheduler.get()
          .scheduleAtFixedRate(
              new RegexCacheStatsReporter(),
              LoggerFactory.getLogger(RegexCacheStatsReporter.class),
              PERIOD_SECONDS,
              PERIOD_SECONDS,
              SECONDS);
    }
  }

  @Override
  public void run(final Logger log) {
    if (log.isDebugEnabled()) {
      log.debug(
          "Regex cache stats: hits={} misses={} admissions={}",
          RegexCache.hits(),
          RegexCache.misses(),
          RegexCache.admissions());
    }
  }
}
