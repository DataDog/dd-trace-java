package datadog.trace.instrumentation.apachehttpclient5;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.net.URI;
import net.bytebuddy.asm.Advice;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;

@AutoService(InstrumenterModule.class)
public class IastHttpUriRequestBaseInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.HasMethodAdvice {

  public IastHttpUriRequestBaseInstrumentation() {
    super("apache-httpclient", "httpclient5");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.hc.client5.http.classic.methods.HttpUriRequestBase";
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new TaintableVisitor(instrumentedType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArguments(String.class, URI.class)),
        IastHttpUriRequestBaseInstrumentation.class.getName() + "$CtorAdvice");
  }

  public static class CtorAdvice {
    @Advice.OnMethodExit()
    @Propagation
    public static void afterCtor(
        @Advice.This final HttpUriRequestBase self, @Advice.Argument(1) final URI uri) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintObjectIfTainted(self, uri);
      }
    }
  }
}
