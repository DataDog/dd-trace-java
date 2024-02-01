package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class CookieInstrumentation extends Instrumenter.Iast implements Instrumenter.ForKnownTypes {

  public CookieInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getName").and(isPublic()).and(takesArguments(0).and(returns(String.class))),
        CookieInstrumentation.class.getName() + "$InstrumenterAdviceGetName");
    transformer.applyAdvice(
        named("getValue").and(isPublic()).and(takesArguments(0).and(returns(String.class))),
        CookieInstrumentation.class.getName() + "$GetValueAdvice");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"jakarta.ws.rs.core.Cookie", "javax.ws.rs.core.Cookie"};
  }

  public static class InstrumenterAdviceGetName {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_NAME)
    public static void onExit(@Advice.Return String cookieName, @Advice.This Object self) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintIfTainted(cookieName, self, SourceTypes.REQUEST_COOKIE_NAME, cookieName);
      }
    }
  }

  public static class GetValueAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void onExit(
        @Advice.Return String cookieValue,
        @Advice.FieldValue("name") String name,
        @Advice.This Object self) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintIfTainted(cookieValue, self, SourceTypes.REQUEST_COOKIE_VALUE, name);
      }
    }
  }
}
