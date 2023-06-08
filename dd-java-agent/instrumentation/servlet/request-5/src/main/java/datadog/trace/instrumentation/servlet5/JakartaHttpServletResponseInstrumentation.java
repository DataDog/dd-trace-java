package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
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
    transformation.applyAdvice(named("addCookie"), getClass().getName() + "$AddCookieAdvice");
    transformation.applyAdvice(
        namedOneOf("setHeader", "addHeader"), getClass().getName() + "$AddHeaderAdvice");
    transformation.applyAdvice(
        namedOneOf("encodeRedirectURL", "encodeURL"), getClass().getName() + "$EncodeURLAdvice");
    transformation.applyAdvice(named("sendRedirect"), getClass().getName() + "$SendRedirectAdvice");
  }

  public static class AddCookieAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final jakarta.servlet.http.Cookie cookie) {
      if (cookie != null) {
        InstrumentationBridge.RESPONSE_HEADER_MODULE.onCookie(
            cookie.getName(), cookie.getSecure(), cookie.isHttpOnly(), false);
      }
    }
  }

  public static class AddHeaderAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final String name, @Advice.Argument(1) String value) {
      if (null != value && value.length() > 0) {
        InstrumentationBridge.RESPONSE_HEADER_MODULE.onHeader(name, value);
      }
    }
  }

  public static class SendRedirectAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final String location) {
      final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
      if (module != null) {
        if (null != location && location.length() > 0) {
          module.onRedirect(location);
        }
      }
    }
  }

  public static class EncodeURLAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) final String url, @Advice.Return String encoded) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        if (null != url && url.length() > 0 && null != encoded && encoded.length() > 0) {
          module.taintIfInputIsTainted(encoded, url);
        }
      }
    }
  }
}
