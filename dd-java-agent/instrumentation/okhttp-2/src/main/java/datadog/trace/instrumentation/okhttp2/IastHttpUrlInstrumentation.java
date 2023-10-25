package datadog.trace.instrumentation.okhttp2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.net.URL;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class IastHttpUrlInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  /**
   * Adding fields to an already loaded class is not possible, during testing
   * com.squareup.okhttp.HttpUrl gets loaded before the instrumenter kicks in, so we must disable
   * the advice transformer or none of the transformations will be applied
   */
  protected static boolean DISABLE_ADVICE_TRANSFORMER = false;

  private final String className = IastHttpUrlInstrumentation.class.getName();

  public IastHttpUrlInstrumentation() {
    super("okhttp", "okhttp-2");
  }

  @Override
  public String instrumentedType() {
    return "com.squareup.okhttp.HttpUrl";
  }

  @Override
  public String muzzleDirective() {
    return "okhttp-2.4";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("parse"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        className + "$ParseAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("get"))
            .and(takesArguments(1))
            .and(takesArgument(0, URL.class)),
        className + "$ParseAdvice");
    transformation.applyAdvice(
        isMethod().and(named("url")).and(takesArguments(0)), className + "$PropagationAdvice");
  }

  @Override
  public AdviceTransformer transformer() {
    return DISABLE_ADVICE_TRANSFORMER
        ? null
        : new VisitingTransformer(new TaintableVisitor(instrumentedType()));
  }

  public static class ParseAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void onParse(
        @Advice.Argument(0) final Object argument, @Advice.Return final Object result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintIfInputIsTainted(result, argument);
      }
    }
  }

  public static class PropagationAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class, inline = false)
    @Propagation
    public static void onPropagation(
        @Advice.This final Object self, @Advice.Return final Object result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintIfInputIsTainted(result, self);
      }
    }
  }
}
