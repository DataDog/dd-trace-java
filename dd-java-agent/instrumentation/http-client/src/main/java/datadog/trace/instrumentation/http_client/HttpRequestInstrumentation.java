package datadog.trace.instrumentation.http_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HttpRequestInstrumentation extends Instrumenter.Tracing {
  public HttpRequestInstrumentation() {
    super("httpclient");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("jdk.internal.net.http.HttpRequestBuilderImpl");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice();
  }
}
