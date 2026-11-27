package datadog.trace.instrumentation.apachehttpcore5;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class IastHttpHostInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public IastHttpHostInstrumentation() {
    super("httpcore-5", "apache-httpcore-5", "apache-http-core-5");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.hc.core5.http.HttpHost";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(hasDescriptor("(Ljava/lang/String;Ljava/net/InetAddress;Ljava/lang/String;I)V")),
        IastHttpHostInstrumentation.class.getName() + "$CtorAdvice");
  }

  public static class CtorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterCtor(
        @Advice.This final Object self, @Advice.Argument(2) final String host) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintObjectIfTainted(self, host);
      }
    }
  }
}
