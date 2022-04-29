package datadog.trace.agent.tooling.bytebuddy.matcher.jfr;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KnownClassesLoaderEvents {

  public interface IClassMatcherCacheEvent {
    void finish(boolean cacheHitOrMIss, String packageName);
  }

  private static final IClassMatcherCacheEvent NOOP_MATCHER_EVENT =
      new IClassMatcherCacheEvent() {
        @Override
        public void finish(boolean cacheHitOrMIss, String packageName) {}
      };

  public void commitEvent(long duration, int classesLoadedBefore) {}

  public IClassMatcherCacheEvent classCachedMatcherEvent(String fqcn) {
    return NOOP_MATCHER_EVENT;
  }

  private static boolean ENABLED = true;

  public static KnownClassesLoaderEvents get() {
    return INSTANCE;
  }

  private static final Logger log = LoggerFactory.getLogger(KnownClassesLoaderEvents.class);
  private static final KnownClassesLoaderEvents INSTANCE =
      ENABLED ? loadKnownClassesLoaderEvents() : new KnownClassesLoaderEvents();

  private static KnownClassesLoaderEvents loadKnownClassesLoaderEvents() {
    if (Platform.isJavaVersionAtLeast(8, 0, 262)) {
      try {
        Instrumenter.class.getClassLoader().loadClass("jdk.jfr.Event");
        return (KnownClassesLoaderEvents)
            Instrumenter.class
                .getClassLoader()
                .loadClass(
                    "datadog.trace.agent.tooling.bytebuddy.matcher.jfr.KnownClassesLoaderEventsImpl")
                .getField("INSTANCE")
                .get(null);
      } catch (Throwable e) {
        log.warn("No JFR events support, falling back to no-op implementation.", e);
      }
    }
    return new KnownClassesLoaderEvents();
  }
}
