package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import ratpack.server.internal.RequestBody;

/**
 * @see RequestBody#readStream() the instrumented method
 */
@AutoService(InstrumenterModule.class)
public class RatpackRequestBodyInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public RatpackRequestBodyInstrumentation() {
    super("ratpack-request-body");
  }

  @Override
  public String instrumentedType() {
    return "ratpack.server.internal.RequestBody";
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("readStream").and(takesArguments(0)), packageName + ".RatpackBodyReadStreamAdvice");
  }
}
