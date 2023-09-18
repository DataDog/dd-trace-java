package datadog.trace.instrumentation.servlet.http;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class CookieInstrumentation extends Instrumenter.Iast implements Instrumenter.ForSingleType {

  public CookieInstrumentation() {
    super("servlet", "servlet-cookie");
  }

  @Override
  public String instrumentedType() {
    return "javax.servlet.http.Cookie";
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("getName")).and(takesArguments(0)),
        getClass().getName() + "$GetNameAdvice");
    transformation.applyAdvice(
        isMethod().and(named("getValue")).and(takesArguments(0)),
        getClass().getName() + "$GetValueAdvice");
  }

  @Override
  public AdviceTransformer transformer() {
    return new VisitingTransformer(new TaintableVisitor(instrumentedType()));
  }

  public static class GetNameAdvice {
    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_COOKIE_NAME)
    public static void afterGetName(
        @Advice.This final Object self, @Advice.Return final String result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_NAME, result, result, self);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterGetName threw", e);
        }
      }
    }
  }

  public static class GetValueAdvice {

    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void afterGetValue(
        @Advice.This final Object self,
        @Advice.FieldValue("name") final String name,
        @Advice.Return final String result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_VALUE, name, result, self);
        } catch (final Throwable e) {
          module.onUnexpectedException("getValue threw", e);
        }
      }
    }
  }
}
