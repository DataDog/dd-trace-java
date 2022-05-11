package datadog.trace.agent.tooling.bytebuddy.matcher.jfr;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatcherCacheEvents {

  public void commitMatcherCacheLoadingEvent(long duration) {}

  public void commitMatcherCacheMissEvent(String fullClassName) {}

  public static MatcherCacheEvents get() {
    return INSTANCE;
  }

  private static final Logger log = LoggerFactory.getLogger(MatcherCacheEvents.class);
  private static final MatcherCacheEvents INSTANCE = loadKnownClassesLoaderEvents();

  private static MatcherCacheEvents loadKnownClassesLoaderEvents() {
    if (Platform.isJavaVersionAtLeast(8, 0, 262)) {
      try {
        Instrumenter.class.getClassLoader().loadClass("jdk.jfr.Event");
        return (MatcherCacheEvents)
            Instrumenter.class
                .getClassLoader()
                .loadClass("datadog.trace.agent.tooling.bytebuddy.jfr.MatcherCacheEventsImpl")
                .getField("INSTANCE")
                .get(null);
      } catch (Throwable e) {
        log.warn("No JFR events support, falling back to the no-op implementation.", e);
      }
    }
    return new MatcherCacheEvents();
  }
}
