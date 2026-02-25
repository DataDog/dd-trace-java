package datadog.trace.instrumentation.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.internal.HttpChannelState.ChannelResponse;

final class JettyResponseInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.internal.HttpChannelState$ChannelResponse";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("succeeded").and(takesNoArguments()),
        JettyResponseInstrumentation.class.getName() + "$ResponseSuccessAdvice");
    transformer.applyAdvice(
        named("failed").and(takesArguments(1)),
        JettyResponseInstrumentation.class.getName() + "$ResponseFailureAdvice");
  }

  public static class ResponseSuccessAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void stopSpan(@Advice.This final Response response) {
      if (response instanceof ChannelResponse) {
        Request req = ((ChannelResponse) response).getRequest();
        JettyServerAdvice.finishSpan(req, response, null);
      }
    }
  }

  public static class ResponseFailureAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final Response response, @Advice.Argument(0) final Throwable failure) {
      if (response instanceof ChannelResponse) {
        Request req = ((ChannelResponse) response).getRequest();
        JettyServerAdvice.finishSpan(req, response, failure);
      }
    }
  }
}
