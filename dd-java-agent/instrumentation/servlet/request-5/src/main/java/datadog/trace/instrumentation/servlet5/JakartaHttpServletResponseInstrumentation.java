package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.HttpResponseHeaderModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.api.iast.util.Cookie;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class JakartaHttpServletResponseInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
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
  protected boolean isOptOutEnabled() {
    return true;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("addCookie")
            .and(takesArguments(1))
            .and(takesArgument(0, named("jakarta.servlet.http.Cookie"))),
        getClass().getName() + "$AddCookieAdvice");
    transformer.applyAdvice(
        namedOneOf("setHeader", "addHeader").and(takesArguments(String.class, String.class)),
        getClass().getName() + "$AddHeaderAdvice");
    transformer.applyAdvice(
        namedOneOf("encodeRedirectURL", "encodeURL")
            .and(takesArgument(0, String.class))
            .and(returns(String.class)),
        getClass().getName() + "$EncodeURLAdvice");
    transformer.applyAdvice(
        named("sendRedirect").and(takesArgument(0, String.class)),
        getClass().getName() + "$SendRedirectAdvice");
  }

  public static class AddCookieAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.RESPONSE_HEADER)
    public static void onEnter(@Advice.Argument(0) final jakarta.servlet.http.Cookie cookie) {
      if (cookie != null) {
        HttpResponseHeaderModule mod = InstrumentationBridge.RESPONSE_HEADER_MODULE;
        if (mod != null) {
          mod.onCookie(
              Cookie.named(cookie.getName())
                  .value(cookie.getValue())
                  .secure(cookie.getSecure())
                  .httpOnly(cookie.isHttpOnly())
                  .maxAge(cookie.getMaxAge())
                  .build());
        }
      }
    }
  }

  public static class AddHeaderAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.RESPONSE_HEADER)
    public static void onEnter(
        @Advice.Argument(0) final String name, @Advice.Argument(1) String value) {
      if (null != value && !value.isEmpty()) {
        HttpResponseHeaderModule mod = InstrumentationBridge.RESPONSE_HEADER_MODULE;
        if (mod != null) {
          mod.onHeader(name, value);
        }
      }
    }
  }

  public static class SendRedirectAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.UNVALIDATED_REDIRECT)
    public static void onEnter(@Advice.Argument(0) final String location) {
      final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
      if (module != null) {
        if (null != location && !location.isEmpty()) {
          module.onRedirect(location);
        }
      }
    }
  }

  public static class EncodeURLAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void onExit(@Advice.Argument(0) final String url, @Advice.Return String encoded) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        if (null != url && !url.isEmpty() && null != encoded && !encoded.isEmpty()) {
          module.taintStringIfTainted(encoded, url);
        }
      }
    }
  }
}
