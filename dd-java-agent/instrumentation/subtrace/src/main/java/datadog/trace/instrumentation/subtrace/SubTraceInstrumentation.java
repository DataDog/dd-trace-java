package datadog.trace.instrumentation.subtrace;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isEquals;
import static net.bytebuddy.matcher.ElementMatchers.isGetter;
import static net.bytebuddy.matcher.ElementMatchers.isHashCode;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isSetter;
import static net.bytebuddy.matcher.ElementMatchers.isToString;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@Slf4j
@AutoService(Instrumenter.class)
public final class SubTraceInstrumentation extends Instrumenter.Default {
  private static final int CUTOFF = 5;

  public SubTraceInstrumentation() {
    super("subtrace");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return new ElementMatcher<TypeDescription>() {
      @Override
      public boolean matches(final TypeDescription target) {
        if (target.getActualName().startsWith("java")
            || target.getActualName().startsWith("jdk")
            || target.getActualName().startsWith("sun")) {
          return false;
        }
        // This is probably a horrible idea, but it's an easy way to get started.
        final String name = target.getActualName();
        final int hash = Math.abs(name.hashCode());
        final int value = hash % 10;
        return CUTOFF < value;
      }
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isPublic()
            .and(
                not(
                    isBridge()
                        .or(isAbstract())
                        .or(isConstructor())
                        .or(isToString())
                        .or(isGetter())
                        .or(isSetter())
                        .or(isHashCode())
                        .or(isEquals())))
            .and(
                new ElementMatcher<MethodDescription>() {
                  @Override
                  public boolean matches(final MethodDescription target) {
                    return true;
                  }
                }),
        packageName + ".SubTraceAdvice");
  }
}
