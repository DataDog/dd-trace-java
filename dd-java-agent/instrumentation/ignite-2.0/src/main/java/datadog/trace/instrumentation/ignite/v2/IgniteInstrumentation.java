package datadog.trace.instrumentation.ignite.v2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;

@AutoService(Instrumenter.class)
public class IgniteInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public IgniteInstrumentation() {
    super("ignite");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.ignite.Ignite";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("org.apache.ignite.IgniteCache", "org.apache.ignite.Ignite");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
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
    transformation.applyAdvice(
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

      InstrumentationContext.get(IgniteCache.class, Ignite.class).put(cache, that);
    }
  }

  public static class IgniteCachesAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This Ignite that,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final Collection<IgniteCache<?, ?>> caches) {

      for (IgniteCache<?, ?> cache : caches) {
        InstrumentationContext.get(IgniteCache.class, Ignite.class).put(cache, that);
      }
    }
  }
}
