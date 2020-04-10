package datadog.trace.instrumentation.mulehttpconnector;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Map;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

@AutoService(Instrumenter.class)
public final class HttpRequesterRequestInstrumentation extends Instrumenter.Default {

  public HttpRequesterRequestInstrumentation() {
    super("mule-http-connector");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.mule.service.http.impl.service.client.async.ResponseAsyncHandler",
        AgentSpan.class.getName());
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.ning.http.client.AsyncHttpClient");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".MuleHttpConnectorDecorator"
      //      packageName + ".HttpRequesterResponseInjectAdapter"
    };
  }

  // TO-DO: might need to specify that it is a nested method
  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("executeRequest")
            .and(takesArgument(0, named("com.ning.http.client.Request")))
            .and(takesArgument(1, named("com.ning.http.client.AsyncHandler")))
            .and(isPublic()),
        packageName + ".HttpRequesterRequestAdvice");
  }
}
