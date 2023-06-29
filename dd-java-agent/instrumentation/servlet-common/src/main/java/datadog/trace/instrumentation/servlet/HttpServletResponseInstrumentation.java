package datadog.trace.instrumentation.servlet;

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
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class HttpServletResponseInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {
  public HttpServletResponseInstrumentation() {
    super("servlet", "servelet-response");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-common";
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServletResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        .and(not(extendsClass(named("javax.servlet.http.HttpServletResponseWrapper"))));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("addCookie").and(takesArgument(0, named("javax.servlet.http.Cookie"))),
        getClass().getName() + "$AddCookieAdvice");
    transformation.applyAdvice(
        namedOneOf("setHeader", "addHeader").and(takesArguments(String.class, String.class)),
        getClass().getName() + "$AddHeaderAdvice");
    transformation.applyAdvice(
        namedOneOf("encodeRedirectURL", "encodeURL")
            .and(takesArgument(0, String.class))
            .and(returns(String.class)),
        getClass().getName() + "$EncodeURLAdvice");
    transformation.applyAdvice(
        named("sendRedirect").and(takesArgument(0, String.class)),
        getClass().getName() + "$SendRedirectAdvice");
  }

  public static class AddCookieAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.RESPONSE_HEADER)
    public static void onEnter(@Advice.Argument(0) final javax.servlet.http.Cookie cookie) {
      if (null != cookie) {
        InstrumentationBridge.RESPONSE_HEADER_MODULE.onCookie(
            cookie.getName(), cookie.getSecure(), false, false);
      }
    }
  }

  public static class AddHeaderAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.RESPONSE_HEADER)
    public static void onEnter(
        @Advice.Argument(0) final String name, @Advice.Argument(1) String value) {
      if (null != value && value.length() > 0) {
        InstrumentationBridge.RESPONSE_HEADER_MODULE.onHeader(name, value);
      }
    }
  }

  public static class SendRedirectAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.UNVALIDATED_REDIRECT)
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
    @Propagation
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
