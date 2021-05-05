package datadog.trace.instrumentation.playws;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class BasePlayWSClientInstrumentation extends Instrumenter.Tracing {
  public BasePlayWSClientInstrumentation() {
    super("play-ws");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("play.shaded.ahc.org.asynchttpclient.AsyncHttpClient");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    // CachingAsyncHttpClient rejects overrides to AsyncHandler
    // It also delegates to another AsyncHttpClient
    return nameStartsWith("play.")
        .<TypeDescription>and(
            implementsInterface(named("play.shaded.ahc.org.asynchttpclient.AsyncHttpClient"))
                .and(not(named("play.api.libs.ws.ahc.cache.CachingAsyncHttpClient"))));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("play.shaded.ahc.org.asynchttpclient.Request")))
            .and(takesArgument(1, named("play.shaded.ahc.org.asynchttpclient.AsyncHandler"))),
        getClass().getName() + "$ClientAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.playws.PlayWSClientDecorator",
      "datadog.trace.instrumentation.playws.HeadersInjectAdapter",
      packageName + ".AsyncHandlerWrapper",
      packageName + ".StreamedAsyncHandlerWrapper"
    };
  }
}
