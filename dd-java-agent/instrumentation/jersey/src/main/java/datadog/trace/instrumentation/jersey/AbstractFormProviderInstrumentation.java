package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
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
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class AbstractFormProviderInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public AbstractFormProviderInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("readFrom").and(isPublic()).and(takesArguments(4)),
        AbstractFormProviderInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.message.internal.AbstractFormProvider";
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class InstrumenterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Return Map<String, List<String>> result,
        @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule prop = InstrumentationBridge.PROPAGATION;
      if (prop == null || result == null || result.isEmpty()) {
        return;
      }
      final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      for (Map.Entry<String, List<String>> entry : result.entrySet()) {
        final String name = entry.getKey();
        prop.taintString(ctx, name, SourceTypes.REQUEST_PARAMETER_NAME, name);
        for (String value : entry.getValue()) {
          prop.taintString(ctx, value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
        }
      }
    }
  }
}
