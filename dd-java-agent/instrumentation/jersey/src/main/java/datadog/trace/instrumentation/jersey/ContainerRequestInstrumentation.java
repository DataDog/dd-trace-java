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
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ContainerRequestInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ContainerRequestInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    String baseName = ContainerRequestInstrumentation.class.getName();
    transformer.applyAdvice(
        named("setProperty").and(isPublic()).and(takesArguments(String.class, Object.class)),
        baseName + "$SetPropertyAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.server.ContainerRequest";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JerseyTaintHelper",
    };
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class SetPropertyAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Argument(0) String name,
        @Advice.Argument(1) Object value,
        @ActiveRequestContext RequestContext reqCtx) {

      if (!"jersey.config.server.representation.decoded.form".equals(name)
          && !"jersey.config.server.representation.form".equals(name)) {
        return;
      }

      final PropagationModule prop = InstrumentationBridge.PROPAGATION;
      if (prop == null) {
        return;
      }

      final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      if (prop.isTainted(ctx, value)) {
        return;
      }
      prop.taintObject(ctx, value, SourceTypes.REQUEST_BODY);
    }
  }
}
