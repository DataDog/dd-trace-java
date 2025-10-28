package datadog.trace.instrumentation.vertx_3_9;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.HttpResponseHeaderModule;
import io.vertx.core.http.Cookie;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class Vertx39HttpServertResponseInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public Vertx39HttpServertResponseInstrumentation() {
    super("vertx", "vertx-3.9", "response");
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        named("addCookie")
            .and(takesArgument(0, named("io.vertx.core.http.Cookie")))
            .and(isPublic()),
        Vertx39HttpServertResponseInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.vertx.core.http.HttpServerResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  protected boolean isOptOutEnabled() {
    return true;
  }

  public static class InstrumenterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.RESPONSE_HEADER)
    public static void onEnter(@Advice.Argument(0) final Cookie cookie) {
      if (cookie != null) {
        HttpResponseHeaderModule mod = InstrumentationBridge.RESPONSE_HEADER_MODULE;
        if (mod != null) {
          mod.onHeader("Set-Cookie", cookie.encode());
        }
      }
    }
  }
}
