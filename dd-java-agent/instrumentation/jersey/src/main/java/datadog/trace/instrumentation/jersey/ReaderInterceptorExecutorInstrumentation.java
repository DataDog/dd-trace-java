package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.InputStream;
import net.bytebuddy.asm.Advice;

// keep in sync with jersey2 (javax packages)
@AutoService(InstrumenterModule.class)
public class ReaderInterceptorExecutorInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ReaderInterceptorExecutorInstrumentation() {
    super("jersey");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.message.internal.ReaderInterceptorExecutor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getInputStream").and(takesArguments(0)),
        getClass().getName() + "$InstrumenterAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class InstrumenterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.Return final InputStream inputStream, @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        module.taintObject(ctx, inputStream, SourceTypes.REQUEST_BODY);
      }
    }
  }
}
