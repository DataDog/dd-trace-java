package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_4_0.server.VertxVersionMatcher.HTTP_1X_SERVER_RESPONSE;
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
import io.vertx.core.http.Cookie;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class CookieImplInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  private final String className = CookieImplInstrumentation.class.getName();

  public CookieImplInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.http.impl.CookieImpl";
  }

  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {HTTP_1X_SERVER_RESPONSE};
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
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_NAME)
    public static void afterGetName(
        @Advice.This final Cookie self, @Advice.Return final String result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_NAME, result, result, self);
      }
    }
  }

  public static class GetValueAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void afterGetValue(
        @Advice.This final Cookie self, @Advice.Return final String result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintIfInputIsTainted(
            SourceTypes.REQUEST_COOKIE_VALUE, self.getName(), result, self);
      }
    }
  }
}
