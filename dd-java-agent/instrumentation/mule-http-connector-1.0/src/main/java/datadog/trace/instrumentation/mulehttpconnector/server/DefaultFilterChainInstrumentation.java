package datadog.trace.instrumentation.mulehttpconnector.server;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class DefaultFilterChainInstrumentation extends Instrumenter.Default {

  public DefaultFilterChainInstrumentation() {
    super("mule-http-connector");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.glassfish.grizzly.filterchain.DefaultFilterChain");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ServerDecorator",
      packageName + ".ExtractAdapter",
      packageName + ".TraceCompletionListener",
      "datadog.trace.instrumentation.mulehttpconnector.ContextAttributes"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isMethod()
            .and(isPrivate())
            .and(named("notifyFailure"))
            .and(takesArgument(0, named("org.glassfish.grizzly.filterchain.FilterChainContext")))
            .and(takesArgument(1, named("java.lang.Throwable"))),
        packageName + ".FilterChainAdvice");
  }
}
