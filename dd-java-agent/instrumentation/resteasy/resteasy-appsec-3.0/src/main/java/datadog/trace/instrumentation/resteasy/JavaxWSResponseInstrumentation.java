package datadog.trace.instrumentation.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.HttpResponseHeaderModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import java.net.URI;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class JavaxWSResponseInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public JavaxWSResponseInstrumentation() {
    super("resteasy");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("header").and(isPublic().and(takesArguments(String.class, Object.class))),
        JavaxWSResponseInstrumentation.class.getName() + "$HeaderAdvice");
    transformer.applyAdvice(
        named("location").and(isPublic().and(takesArguments(URI.class))),
        JavaxWSResponseInstrumentation.class.getName() + "$RedirectionAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.ws.rs.core.Response$ResponseBuilder";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  protected boolean isOptOutEnabled() {
    return true;
  }

  public static class HeaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.RESPONSE_HEADER)
    public static void onExit(
        @Advice.Argument(0) String headerName, @Advice.Argument(1) Object headerValue) {
      if (headerValue instanceof String) {
        String value = (String) headerValue;
        if (value.length() > 0) {
          HttpResponseHeaderModule mod = InstrumentationBridge.RESPONSE_HEADER_MODULE;
          if (mod != null) {
            mod.onHeader(headerName, value);
          }
        }
      }
    }
  }

  public static class RedirectionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.UNVALIDATED_REDIRECT)
    public static void onEnter(@Advice.Argument(0) final URI location) {
      final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
      if (module != null) {
        if (null != location) {
          module.onURIRedirect(location);
        }
      }
    }
  }
}
