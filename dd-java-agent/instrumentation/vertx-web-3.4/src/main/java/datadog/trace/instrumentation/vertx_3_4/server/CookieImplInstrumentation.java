package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.PARSABLE_HEADER_VALUE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.VIRTUAL_HOST_HANDLER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import io.vertx.ext.web.Cookie;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class CookieImplInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  private final String className = CookieImplInstrumentation.class.getName();

  public CookieImplInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.CookieImpl";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {PARSABLE_HEADER_VALUE, VIRTUAL_HOST_HANDLER};
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("getName")).and(takesArguments(0)), className + "$GetNameAdvice");
    transformation.applyAdvice(
        isMethod().and(named("getValue")).and(takesArguments(0)), className + "$GetValueAdvice");
  }

  @Override
  public AdviceTransformer transformer() {
    return new VisitingTransformer(new TaintableVisitor(this.instrumentedType()));
  }

  public static class GetNameAdvice {
    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_COOKIE_NAME)
    public static void afterGetName(
        @Advice.This final Cookie self, @Advice.Return final String result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_NAME, result, result, self);
        } catch (final Throwable e) {
          module.onUnexpectedException("getName threw", e);
        }
      }
    }
  }

  public static class GetValueAdvice {

    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void afterGetValue(
        @Advice.This final Cookie self, @Advice.Return final String result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          // TODO calling self.getName() actually taints the name of the cookie
          module.taintIfInputIsTainted(
              SourceTypes.REQUEST_COOKIE_VALUE, self.getName(), result, self);
        } catch (final Throwable e) {
          module.onUnexpectedException("getValue threw", e);
        }
      }
    }
  }
}
