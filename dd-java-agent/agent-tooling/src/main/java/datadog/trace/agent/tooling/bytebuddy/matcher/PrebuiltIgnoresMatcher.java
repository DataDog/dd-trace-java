package datadog.trace.agent.tooling.bytebuddy.matcher;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.agent.tooling.bytebuddy.matcher.jfr.MatcherCacheEvents;
import datadog.trace.agent.tooling.matchercache.MatcherCache;
import datadog.trace.util.AgentTaskScheduler;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import javax.annotation.Nullable;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrebuiltIgnoresMatcher<T extends TypeDescription>
    extends ElementMatcher.Junction.ForNonNullValues<T> {
  // TODO unit test

  private static final Logger log = LoggerFactory.getLogger(PrebuiltIgnoresMatcher.class);

  @Nullable
  public static <T extends TypeDescription> ElementMatcher.Junction<T> create(
      String preBuiltMatcherData, ElementMatcher<TypeDescription> fallbackIgnoresMatcher) {
    File cacheFile = new File(preBuiltMatcherData);
    MatcherCache matcherCache = null;
    try (InputStream is = Files.newInputStream(cacheFile.toPath())) {
      long startAt = System.nanoTime();
      matcherCache = MatcherCache.deserialize(is);
      long durationNs = System.nanoTime() - startAt;
      log.info("Loaded pre-built matcher data in ms: {}", durationNs / 1_000_000);
      // Tracking duration manually because JFR doesn't seem to track duration properly at this
      // early stage
      commitCacheLoadingTimeEvent(durationNs);
    } catch (Throwable e) {
      log.error("Failed to load pre-build ignores matcher data from: " + preBuiltMatcherData, e);
    }
    return matcherCache == null
        ? null
        : new PrebuiltIgnoresMatcher<T>(matcherCache, fallbackIgnoresMatcher);
  }

  private final MatcherCache matcherCache;
  private final ElementMatcher<TypeDescription> fallbackIgnoresMatcher;

  public PrebuiltIgnoresMatcher(
      MatcherCache matcherCache, ElementMatcher<TypeDescription> fallbackIgnoresMatcher) {
    this.matcherCache = matcherCache;
    this.fallbackIgnoresMatcher = fallbackIgnoresMatcher;
  }

  @Override
  protected boolean doMatch(T target) {
    String fqcn = target.getActualName();
    switch (matcherCache.transform(fqcn)) {
      case SKIP:
        return true;
      case UNKNOWN:
        MatcherCacheEvents.get().commitMatcherCacheMissEvent(fqcn);
        if (fallbackIgnoresMatcher != null) {
          return fallbackIgnoresMatcher.matches(target);
        }
    }
    return false;
  }

  private static void commitCacheLoadingTimeEvent(final long durationNs) {
    // Delay committing event because JFR could not been initialized yet and committing immediately
    // will
    // void this event
    AgentTaskScheduler.INSTANCE.schedule(
        new AgentTaskScheduler.Task<Boolean>() {
          @Override
          public void run(Boolean target) {
            MatcherCacheEvents.get().commitMatcherCacheLoadingEvent(durationNs);
          }
        },
        true,
        1500,
        MILLISECONDS);
  }
}
