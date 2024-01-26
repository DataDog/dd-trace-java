package datadog.trace.instrumentation.apachehttpcore;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class IastHttpHostInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public IastHttpHostInstrumentation() {
    super("httpcore", "apache-httpcore", "apache-http-core");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.http.HttpHost";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArguments(String.class, int.class, String.class)),
        IastHttpHostInstrumentation.class.getName() + "$CtorAdvice");
  }

  public static class CtorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterCtor(
        @Advice.This final Object self, @Advice.Argument(0) final Object argument) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintIfTainted(self, argument);
      }
    }
  }
}
