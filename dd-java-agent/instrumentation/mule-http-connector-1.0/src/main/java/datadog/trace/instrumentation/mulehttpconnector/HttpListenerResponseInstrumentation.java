package datadog.trace.instrumentation.mulehttpconnector;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Map;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

@AutoService(Instrumenter.class)
public final class HttpListenerResponseInstrumentation extends Instrumenter.Default {

  public HttpListenerResponseInstrumentation() {
    super("mule-http-connector");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.mule.extension.http.internal.listener.HttpListenerResponseSender");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".MuleHttpConnectorDecorator"};
  }

  // TO-DO: might need to specify that it is a nested method
  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("sendResponse")
            .and(
                takesArgument(
                    0, named("org.mule.extension.http.internal.listener.HttpResponseContext")))
            .and(
                takesArgument(
                    1,
                    named(
                        "org.mule.extension.http.api.listener.builder.HttpListenerResponseBuilder")))
            .and(
                takesArgument(
                    2,
                    named(
                        "org.mule.runtime.extension.api.runtime.source.SourceCompletionCallback")))
            // NOTE: dependency not added to gradle
            .and(isPublic()),
        packageName + ".MuleHttpConnectorAdvice");
  }
}
