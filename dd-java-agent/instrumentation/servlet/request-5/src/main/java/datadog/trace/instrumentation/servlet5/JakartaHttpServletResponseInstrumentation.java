package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.InsecureCookieModule;
import jakarta.servlet.http.Cookie;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JakartaHttpServletResponseInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {
  public JakartaHttpServletResponseInstrumentation() {
    super("servlet", "servlet-5", "servlet-response");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.http.HttpServletResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        .and(not(extendsClass(named("jakarta.servlet.http.HttpServletResponseWrapper"))));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(named("addCookie"), this.getClass().getName() + "$AddCookieAdvice");
    transformation.applyAdvice(
        namedOneOf("setHeader", "addHeader"), this.getClass().getName() + "$AddHeaderAdvice");
  }

  public static class AddCookieAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final Cookie cookie) {
      final InsecureCookieModule module = InstrumentationBridge.INSECURE_COOKIE;
      if (module != null) {
        if (null != cookie) {
          module.onCookie(cookie.getName(), cookie.getSecure());
        }
      }
    }
  }

  public static class AddHeaderAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final String name, @Advice.Argument(1) String value) {
      final InsecureCookieModule module = InstrumentationBridge.INSECURE_COOKIE;
      if (module != null) {
        if ("Set-Cookie".equalsIgnoreCase(name) && null != value && value.length() > 0) {
          module.onCookieHeader(value);
        }
      }
    }
  }
}
