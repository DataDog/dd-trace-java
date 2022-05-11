package datadog.trace.agent.tooling.bytebuddy.jfr;

import datadog.trace.agent.tooling.bytebuddy.matcher.jfr.MatcherCacheEvents;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

public final class MatcherCacheEventsImpl extends MatcherCacheEvents {
  // accessed by reflection if JFR support is detected
  public static MatcherCacheEvents INSTANCE = new MatcherCacheEventsImpl();

  @Override
  public void commitMatcherCacheLoadingEvent(long durationNs) {
    MatcherCacheLoadingEvent evt = new MatcherCacheLoadingEvent(durationNs);
    if (evt.shouldCommit()) {
      evt.commit();
    }
  }

  @Override
  public void commitMatcherCacheMissEvent(String fullClassName) {
    MatcherCacheMissEventImpl evt = new MatcherCacheMissEventImpl(fullClassName);
    if (evt.shouldCommit()) {
      evt.commit();
    }
  }

  @Category({"Datadog", "Tracer"})
  @Name("datadog.trace.agent.MatcherCacheLoading")
  @Label("Matcher Cache Loading")
  @StackTrace(false)
  public static final class MatcherCacheLoadingEvent extends Event {
    @Label("durationNs")
    final long durationNs;

    public MatcherCacheLoadingEvent(long durationNs) {
      this.durationNs = durationNs;
    }
  }

  @Category({"Datadog", "Tracer"})
  @Name("datadog.trace.agent.MatcherCacheMiss")
  @Label("Matcher Cache Miss")
  @StackTrace(false)
  public static final class MatcherCacheMissEventImpl extends Event {
    @Label("Package Name")
    final String packageName;

    @Label("Class Name")
    final String className;

    public MatcherCacheMissEventImpl(String fullClassName) {
      int packageEndsAt = fullClassName.lastIndexOf('.');
      packageName = fullClassName.substring(0, Math.max(packageEndsAt, 0));
      className = fullClassName.substring(packageEndsAt + 1);
    }
  }
}
