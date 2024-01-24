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
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new TaintableVisitor(this.instrumentedType()));
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getName")).and(takesArguments(0)), className + "$GetNameAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getValue")).and(takesArguments(0)), className + "$GetValueAdvice");
  }

  public static class GetNameAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_NAME)
    public static void afterGetName(
        @Advice.This final Cookie self, @Advice.Return final String result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintIfTainted(result, self, SourceTypes.REQUEST_COOKIE_NAME, result);
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
        // TODO calling self.getName() actually taints the name of the cookie
        module.taintIfTainted(result, self, SourceTypes.REQUEST_COOKIE_VALUE, self.getName());
      }
    }
  }
}
