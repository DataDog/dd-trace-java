package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;

@AutoService(Instrumenter.class)
public class RequestDecodingEndInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForKnownTypes {

  public RequestDecodingEndInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.vertx.core.http.impl.Http2ServerRequestImpl",
      "io.vertx.core.http.impl.HttpServerRequestImpl",
    };
  }

  private IReferenceMatcher postProcessReferenceMatcher(final ReferenceMatcher origMatcher) {
    return new IReferenceMatcher.ConjunctionReferenceMatcher(
        origMatcher, VertxVersionMatcher.INSTANCE);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MultiMapAsMap",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("endDecode")
            .and(takesArguments(0))
            .or(named("handleEnd").and(takesArgument(0, named("io.vertx.core.MultiMap")))),
        packageName + ".RequestDecodingEndAdvice");
  }
}
