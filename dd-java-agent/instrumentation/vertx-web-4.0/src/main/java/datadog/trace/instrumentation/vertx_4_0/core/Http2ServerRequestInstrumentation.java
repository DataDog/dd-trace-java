package datadog.trace.instrumentation.vertx_4_0.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class Http2ServerRequestInstrumentation extends AbstractHttpServerRequestInstrumentation
    implements Instrumenter.ForKnownTypes {

  @Override
  protected ElementMatcher.Junction<MethodDescription> attributesFilter() {
    return isPublic().and(named("formAttributes"));
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.vertx.core.http.impl.Http2ServerRequest", "io.vertx.core.http.impl.Http2ServerRequestImpl"
    };
  }
}
