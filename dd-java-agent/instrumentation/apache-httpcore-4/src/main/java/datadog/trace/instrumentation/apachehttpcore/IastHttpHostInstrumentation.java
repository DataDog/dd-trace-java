package datadog.trace.instrumentation.apachehttpcore;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import org.apache.http.HttpHost;

@AutoService(InstrumenterModule.class)
public class IastHttpHostInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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
    transformer.applyAdvice(
        named("toURI").and(isMethod()).and(takesArguments(0)),
        IastHttpHostInstrumentation.class.getName() + "$ToUriAdvice");
  }

  public static class CtorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterCtor(
        @Advice.This final Object self, @Advice.Argument(0) final Object argument) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintObjectIfTainted(self, argument);
      }
    }
  }

  public static class ToUriAdvice {
    @Advice.OnMethodExit()
    @Propagation
    public static void methodExit(@Advice.This HttpHost self, @Advice.Return String result) {
      final PropagationModule propagationModule = InstrumentationBridge.PROPAGATION;
      if (propagationModule != null) {
        propagationModule.taintObjectIfTainted(result, self);
      }
    }
  }
}
