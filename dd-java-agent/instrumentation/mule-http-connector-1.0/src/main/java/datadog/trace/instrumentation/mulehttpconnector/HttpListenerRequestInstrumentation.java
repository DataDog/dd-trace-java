package datadog.trace.instrumentation.mulehttpconnector;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Map;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

@AutoService(Instrumenter.class)
public final class HttpListenerRequestInstrumentation extends Instrumenter.Default {

  public HttpListenerRequestInstrumentation() {
    super("mule-http-connector");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(
        named("org.mule.extension.http.internal.listener.server.ModuleRequestHandler"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".MuleHttpConnectorDecorator"};
  }

  // TO-DO: might need to specify that it is a nested method
  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("handleRead")
            .and(takesArgument(0, named("org.glassfish.grizzly.filterchain.FilterChainContext")))
            .and(isPublic()),
        packageName + ".HttpListenerRequestAdvice");
  }

  //  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
  //    return singletonMap(
  //      named("handleRead")
  //        .and(
  //          takesArgument(
  //            0, named("org.mule.runtime.http.api.domain.request.HttpRequestContext")))
  //        .and(
  //          takesArgument(
  //            1, named("org.mule.runtime.http.api.server.async.HttpResponseReadyCallback")))
  //        .and(
  //          takesArgument(
  //            2,
  //            named(
  //              "org.mule.runtime.extension.api.runtime.source.SourceCompletionCallback")))
  //        // NOTE: dependency not added to gradle
  //        .and(isPublic()),
  //      packageName + ".MuleHttpConnectorAdvice");
  //  }
}
