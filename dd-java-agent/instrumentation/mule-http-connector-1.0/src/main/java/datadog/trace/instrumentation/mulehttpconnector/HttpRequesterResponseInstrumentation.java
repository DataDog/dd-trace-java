package datadog.trace.instrumentation.mulehttpconnector;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Collections;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

@AutoService(Instrumenter.class)
public final class HttpRequesterResponseInstrumentation extends Instrumenter.Default {

  public HttpRequesterResponseInstrumentation() {
    super("mule-http-connector");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.mule.service.http.impl.service.client.async.ResponseAsyncHandler",
        AgentSpan.class.getName());
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.mule.service.http.impl.service.client.async.ResponseAsyncHandler");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".MuleHttpConnectorDecorator"};
  }

  // TO-DO: might need to specify that it is a nested method
  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("onCompleted")
            .and(takesArgument(0, named("com.ning.http.client.Response")))
            .and(isPublic()),
        packageName + ".HttpRequesterResponseAdvice");
  }
}
