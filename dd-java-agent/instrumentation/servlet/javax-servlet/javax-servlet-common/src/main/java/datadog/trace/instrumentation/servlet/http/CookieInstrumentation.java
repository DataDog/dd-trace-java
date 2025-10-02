package datadog.trace.instrumentation.servlet.http;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class CookieInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.HasMethodAdvice {

  public CookieInstrumentation() {
    super("servlet", "servlet-cookie");
  }

  @Override
  public String instrumentedType() {
    return "javax.servlet.http.Cookie";
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new TaintableVisitor(instrumentedType()));
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getName")).and(takesArguments(0)),
        getClass().getName() + "$GetNameAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getValue")).and(takesArguments(0)),
        getClass().getName() + "$GetValueAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetNameAdvice {
    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_COOKIE_NAME)
    public static void afterGetName(
        @Advice.This final Object self,
        @Advice.Return final String result,
        @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
          module.taintStringIfTainted(ctx, result, self, SourceTypes.REQUEST_COOKIE_NAME, result);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterGetName threw", e);
        }
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetValueAdvice {

    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void afterGetValue(
        @Advice.This final Object self,
        @Advice.FieldValue("name") final String name,
        @Advice.Return final String result,
        @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
          module.taintStringIfTainted(ctx, result, self, SourceTypes.REQUEST_COOKIE_VALUE, name);
        } catch (final Throwable e) {
          module.onUnexpectedException("getValue threw", e);
        }
      }
    }
  }
}
