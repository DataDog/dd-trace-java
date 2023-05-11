package datadog.trace.instrumentation.vertx_3_9;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.InsecureCookieModule;
import io.vertx.core.http.Cookie;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class Vertx4HttpServertResponseInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {
  public Vertx4HttpServertResponseInstrumentation() {
    super("vertx", "vertx-4.0", "response");
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("addCookie")
            .and(takesArgument(0, named("io.vertx.core.http.Cookie")))
            .and(isPublic()),
        Vertx4HttpServertResponseInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.vertx.core.http.HttpServerResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  public static class InstrumenterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final Cookie cookie) {
      final InsecureCookieModule module = InstrumentationBridge.INSECURE_COOKIE;
      if (module != null) {
        InstrumentationBridge.onHeader("Set-Cookie", cookie.encode());
      }
    }
  }
}
