package datadog.trace.instrumentation.apachehttpcore5;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.net.URI;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class BasicHttpRequestInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public BasicHttpRequestInstrumentation() {
    super("testApache");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.hc.core5.http.message.BasicHttpRequest";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArguments(String.class, URI.class)),
        BasicHttpRequestInstrumentation.class.getName() + "$CtorAdvice");
  }

  public static class CtorAdvice {
    @Advice.OnMethodExit()
    public static void afterCtor() {
      System.out.println("CtorAdvice.afterCtor");
    }
  }
}
