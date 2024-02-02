package datadog.trace.instrumentation.commonshttpclient;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class IastHttpMethodBaseInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  private final String className = IastHttpMethodBaseInstrumentation.class.getName();

  public IastHttpMethodBaseInstrumentation() {
    super("commons-http-client");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.commons.httpclient.HttpMethodBase";
  }

  @Override
  public String muzzleDirective() {
    return "commons-http-client-x";
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new TaintableVisitor(instrumentedType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArguments(1)).and(takesArgument(0, String.class)),
        className + "$CtorAdvice");
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
