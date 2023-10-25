package datadog.trace.instrumentation.vertx_4_0.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class Http1xServerRequestInstrumentation extends AbstractHttpServerRequestInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  protected ElementMatcher.Junction<MethodDescription> attributesFilter() {
    return isPrivate().and(named("attributes"));
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.http.impl.Http1xServerRequest";
  }
}
