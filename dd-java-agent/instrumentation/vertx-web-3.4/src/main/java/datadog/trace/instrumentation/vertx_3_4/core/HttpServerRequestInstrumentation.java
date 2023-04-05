package datadog.trace.instrumentation.vertx_3_4.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HttpServerRequestInstrumentation extends AbstractHttpServerRequestInstrumentation {

  @Override
  protected ElementMatcher.Junction<MethodDescription> attributesFilter() {
    return isPrivate().and(named("attributes"));
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.http.impl.HttpServerRequestImpl";
  }
}
