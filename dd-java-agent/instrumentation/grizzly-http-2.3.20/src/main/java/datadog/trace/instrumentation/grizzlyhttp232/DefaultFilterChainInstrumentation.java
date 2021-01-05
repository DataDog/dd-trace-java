package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class DefaultFilterChainInstrumentation extends Instrumenter.Tracing {

  public DefaultFilterChainInstrumentation() {
    super("grizzly-filterchain");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.glassfish.grizzly.filterchain.DefaultFilterChain");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GrizzlyDecorator",
      packageName + ".HTTPRequestPacketURIDataAdapter",
      packageName + ".ExtractAdapter"
    };
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isMethod()
            .and(isPrivate())
            .and(named("notifyFailure"))
            .and(takesArgument(0, named("org.glassfish.grizzly.filterchain.FilterChainContext")))
            .and(takesArgument(1, named("java.lang.Throwable"))),
        packageName + ".DefaultFilterChainAdvice");
  }
}
