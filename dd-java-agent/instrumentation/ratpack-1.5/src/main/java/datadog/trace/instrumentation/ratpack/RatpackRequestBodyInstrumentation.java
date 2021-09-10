package datadog.trace.instrumentation.ratpack;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class RatpackRequestBodyInstrumentation extends Instrumenter.AppSec {
  public RatpackRequestBodyInstrumentation() {
    super("ratpack-request-body");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("ratpack.server.internal.RequestBody");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RequestBodyCollectionPublisher$ByteBufIntoByteBufferCallback",
      packageName + ".RequestBodyCollectionPublisher",
      packageName + ".RequestBodyCollectionPublisher$1",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("readStream").and(takesArguments(0)), packageName + ".RatpackBodyReadStreamAdvice");
  }
}
