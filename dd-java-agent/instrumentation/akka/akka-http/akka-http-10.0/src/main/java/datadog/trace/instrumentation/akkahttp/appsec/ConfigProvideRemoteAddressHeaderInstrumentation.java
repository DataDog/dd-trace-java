package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

public class ConfigProvideRemoteAddressHeaderInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "com.typesafe.config.impl.SimpleConfig";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(named("getBoolean"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class))
            .and(returns(boolean.class)),
        ConfigProvideRemoteAddressHeaderInstrumentation.class.getName()
            + "$EnableRemoteAddressHeaderAdvice");
  }

  static class EnableRemoteAddressHeaderAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.Argument(0) String configName) {
      // ideally we'd use remote-address-attribute, but that's only available on 10.2,
      // and doesn't work on http/2 until 10.2.3
      return "remote-address-header".equals(configName);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void exit(@Advice.Enter boolean enter, @Advice.Return(readOnly = false) boolean ret) {
      if (enter) {
        ret = true;
      }
    }
  }
}
