package datadog.trace.instrumentation.play26;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import play.api.mvc.request.RemoteConnection;

/** Attempt to save the original ip address of the connection. */
@AutoService(Instrumenter.class)
public class SavePeerAddressInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public SavePeerAddressInstrumentation() {
    super("play");
  }

  @Override
  public String instrumentedType() {
    return "play.core.server.common.ForwardedHeaderHandler";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RemoteConnectionWithRawAddress",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("forwardedConnection")
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, named("play.api.mvc.request.RemoteConnection")))
            .and(takesArgument(1, named("play.api.mvc.Headers")))
            .and(returns(named("play.api.mvc.request.RemoteConnection"))),
        SavePeerAddressInstrumentation.class.getName() + "$SaveRawConnectionAdvice");
  }

  public static class SaveRawConnectionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.Argument(0) RemoteConnection rawConnection,
        @Advice.Return(readOnly = false) RemoteConnection returnConnection) {
      if (rawConnection == null || returnConnection == null) {
        return;
      }

      if (returnConnection.remoteAddress() == rawConnection.remoteAddress()) {
        return;
      }

      returnConnection =
          new RemoteConnectionWithRawAddress(returnConnection, rawConnection.remoteAddress());
    }
  }
}
