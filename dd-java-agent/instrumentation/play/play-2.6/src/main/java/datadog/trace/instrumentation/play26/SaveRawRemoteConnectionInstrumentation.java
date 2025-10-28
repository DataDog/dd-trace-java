package datadog.trace.instrumentation.play26;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import play.api.mvc.request.RemoteConnection;

@AutoService(InstrumenterModule.class)
public class SaveRawRemoteConnectionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public SaveRawRemoteConnectionInstrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play26Plus";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".RemoteConnectionWithRawAddress"};
  }

  @Override
  public String instrumentedType() {
    return "play.core.server.common.ForwardedHeaderHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("forwardedConnection")
            .and(not(isStatic()))
            .and(takesArguments(2))
            .and(takesArgument(0, named("play.api.mvc.request.RemoteConnection")))
            .and(takesArgument(1, named("play.api.mvc.Headers")))
            .and(returns(named("play.api.mvc.request.RemoteConnection"))),
        SaveRawRemoteConnectionInstrumentation.class.getName() + "$ForwardedConnectionAdvice");
  }

  static class ForwardedConnectionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.Argument(0) RemoteConnection rawConnection,
        @Advice.Return(readOnly = false) RemoteConnection retConnection) {
      if (rawConnection != retConnection && rawConnection != null) {
        retConnection = new RemoteConnectionWithRawAddress(rawConnection, retConnection);
      }
    }
  }
}
