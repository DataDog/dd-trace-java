package datadog.trace.instrumentation.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

/**
 * Response-phase AppSec blocking for Jetty 12: runs the response IG callbacks (status and response
 * headers) before the first write commits the response. Every environment (ee8, ee9, ee10) and the
 * error responses ({@code ErrorResponse} does not override {@code write}) end in this method.
 */
@AutoService(InstrumenterModule.class)
public final class JettyCommitResponseInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JettyCommitResponseInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.internal.HttpChannelState$ChannelResponse";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".JettyDecorator",
      packageName + ".JettyBlockResponseFunction",
      packageName + ".JettyBlockResponseHelper",
      packageName + ".RequestURIDataAdapter",
      packageName + ".JettyCommitResponseHelper",
      packageName + ".JettyCommitResponseHelper$BlockingCallback"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("write")
            .and(isPublic())
            .and(takesArguments(3))
            .and(takesArgument(0, boolean.class))
            .and(takesArgument(1, named("java.nio.ByteBuffer")))
            .and(takesArgument(2, named("org.eclipse.jetty.util.Callback"))),
        packageName + ".JettyCommitResponseAdvice");
  }
}
