package datadog.trace.instrumentation.vertx_3_4.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class Http2ServerRequestInstrumentation extends AbstractHttpServerRequestInstrumentation {

  @Override
  protected ElementMatcher.Junction<MethodDescription> attributesFilter() {
    return isPublic().and(named("formAttributes"));
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.http.impl.Http2ServerRequestImpl";
  }
}
