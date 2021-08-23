package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This instrumentation only supports transfer of the active span at connection time to the
 * underlying Netty Channel.
 *
 * <p>Based on the OpenTelemetry Reactor Netty instrumentation.
 * https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/reactor-netty/reactor-netty-1.0/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/reactornetty/v1_0/HttpClientInstrumentation.java
 */
@AutoService(Instrumenter.class)
public class HttpClientInstrumentation extends Instrumenter.Tracing {

  public HttpClientInstrumentation() {
    super("reactor-netty", "reactor-netty-1");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Introduced in 1.0.0
    return hasClassesNamed("reactor.netty.transport.AddressUtils");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.netty41.AttributeKeys",
      packageName + ".CaptureConnectSpan",
      packageName + ".TransferConnectSpan",
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("reactor.netty.http.client.HttpClient");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isStatic()).and(namedOneOf("create", "newConnection")),
        packageName + ".AfterConstructorAdvice");
  }
}
