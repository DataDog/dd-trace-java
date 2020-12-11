package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperClass;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
public final class FilterInstrumentation extends Instrumenter.Tracing {

  public FilterInstrumentation() {
    super("grizzly-filterchain");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("org.glassfish.grizzly.filterchain.BaseFilter");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return hasSuperClass(named("org.glassfish.grizzly.filterchain.BaseFilter"))
        // HttpCodecFilter is instrumented in the server instrumentation
        .and(
            not(
                ElementMatchers.<TypeDescription>named(
                    "org.glassfish.grizzly.http.HttpCodecFilter")))
        .and(
            not(
                ElementMatchers.<TypeDescription>named(
                    "org.glassfish.grizzly.http.HttpServerFilter")));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("handleRead")
            .and(takesArgument(0, named("org.glassfish.grizzly.filterchain.FilterChainContext")))
            .and(isPublic()),
        packageName + ".FilterAdvice");
  }
}
