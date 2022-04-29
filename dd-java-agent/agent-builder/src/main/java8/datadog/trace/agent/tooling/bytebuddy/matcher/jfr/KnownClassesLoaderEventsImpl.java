package datadog.trace.agent.tooling.bytebuddy.matcher.jfr;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

public class KnownClassesLoaderEventsImpl extends KnownClassesLoaderEvents {

  public static KnownClassesLoaderEvents INSTANCE = new KnownClassesLoaderEventsImpl();

  @Category({"Datadog", "Tracer"})
  @Name("datadog.trace.agent.KnownClassesLoader")
  @Label("Known Classes Loader")
  @StackTrace(false)
  @Enabled(true)
  // TODO rename to ClassMatcherCacheLoadingEvent
  public class KnownClassesLoaderEvent extends Event {
    @Label("durationNs")
    final long durationNs;

    public KnownClassesLoaderEvent(long durationNs) {
      this.durationNs = durationNs;
    }
  }

  @Override
  public void commitEvent(long durationNs, int classesLoadedBefore) {
    final KnownClassesLoaderEvent evt = new KnownClassesLoaderEvent(durationNs);
    evt.commit();
    System.out.println(
        ">>>>> KnownClassesLoaderEvent committed: "
            + durationNs
            + " classes loaded before "
            + classesLoadedBefore);
  }

  @Category({"Datadog", "Tracer"})
  @Name("datadog.trace.agent.ClassMatcherCache")
  @Label("Class Matcher Cache")
  @StackTrace(false)
  @Enabled(true)
  public class ClassMatcherCacheEvent extends Event implements IClassMatcherCacheEvent {
    @Label("fqcn")
    final String fqcn;

    @Label("packagename")
    String packagename;

    @Label("hit")
    boolean hit;

    public ClassMatcherCacheEvent(String fqcn) {
      this.fqcn = fqcn;
    }

    @Override
    public void finish(boolean cacheHitOrMIss, String packagename) {
      end();
      this.packagename = packagename;
      this.hit = cacheHitOrMIss;
      if (shouldCommit()) {
        commit();
      }
    }
  }

  @Category({"Datadog", "Tracer"})
  @Name("datadog.trace.agent.GlobalMatcherTransformed")
  @Label("Class Matcher Cache")
  @StackTrace(false)
  @Enabled(true)
  public class GlobalMatcherTransformedEvent extends Event {
    @Label("fqcn")
    final String fqcn;

    public GlobalMatcherTransformedEvent(String fqcn) {
      this.fqcn = fqcn;
    }
  }

  @Override
  public IClassMatcherCacheEvent classCachedMatcherEvent(String fqcn) {
    ClassMatcherCacheEvent evt = new ClassMatcherCacheEvent(fqcn);
    evt.begin();
    return evt;
  }

  @Override
  public void globalMatcherTransformedEvent(String fqcn) {
    GlobalMatcherTransformedEvent evt = new GlobalMatcherTransformedEvent(fqcn);
    if (evt.shouldCommit()) {
      evt.commit();
    }
  }
}
