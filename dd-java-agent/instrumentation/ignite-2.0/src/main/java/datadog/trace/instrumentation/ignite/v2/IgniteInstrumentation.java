package datadog.trace.instrumentation.ignite.v2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collection;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;

public final class IgniteInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.ignite.Ignite";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(
                namedOneOf(
                    "createCache",
                    "getOrCreateCache",
                    "cache",
                    "createNearCache",
                    "getOrCreateNearCache"))
            .and(returns(hasInterface(named("org.apache.ignite.IgniteCache")))),
        IgniteInstrumentation.class.getName() + "$IgniteCacheAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(namedOneOf("createCaches", "getOrCreateCaches"))
            .and(returns(hasInterface(named("java.util.Collection")))),
        IgniteInstrumentation.class.getName() + "$IgniteCachesAdvice");
  }

  public static class IgniteCacheAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This Ignite that,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final IgniteCache<?, ?> cache) {
      if (cache != null) {
        InstrumentationContext.get(IgniteCache.class, Ignite.class).put(cache, that);
      }
    }
  }

  public static class IgniteCachesAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This Ignite that,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final Collection<IgniteCache<?, ?>> caches) {

      if (caches != null) {
        for (IgniteCache<?, ?> cache : caches) {
          InstrumentationContext.get(IgniteCache.class, Ignite.class).put(cache, that);
        }
      }
    }
  }
}
