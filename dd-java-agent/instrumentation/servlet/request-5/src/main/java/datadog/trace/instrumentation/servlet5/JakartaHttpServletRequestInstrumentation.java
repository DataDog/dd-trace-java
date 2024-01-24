package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.iast.TaintableEnumeration;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import jakarta.servlet.http.Cookie;
import java.util.Enumeration;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@SuppressWarnings("unused")
@AutoService(Instrumenter.class)
public class JakartaHttpServletRequestInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {

  private static final String CLASS_NAME = JakartaHttpServletRequestInstrumentation.class.getName();
  private static final ElementMatcher.Junction<? super TypeDescription> WRAPPER_CLASS =
      named("jakarta.servlet.http.HttpServletRequestWrapper");

  public JakartaHttpServletRequestInstrumentation() {
    super("servlet", "servlet-5", "servlet-request");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.http.HttpServletRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        .and(not(WRAPPER_CLASS))
        .and(not(extendsClass(WRAPPER_CLASS)));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.agent.tooling.iast.TaintableEnumeration"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getHeader")).and(takesArguments(String.class)),
        CLASS_NAME + "$GetHeaderAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getHeaders")).and(takesArguments(String.class)),
        CLASS_NAME + "$GetHeadersAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getHeaderNames")).and(takesArguments(0)),
        CLASS_NAME + "$GetHeaderNamesAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getParameter")).and(takesArguments(String.class)),
        CLASS_NAME + "$GetParameterAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getParameterValues")).and(takesArguments(String.class)),
        CLASS_NAME + "$GetParameterValuesAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getParameterMap")).and(takesArguments(0)),
        CLASS_NAME + "$GetParameterMapAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getParameterNames")).and(takesArguments(0)),
        CLASS_NAME + "$GetParameterNamesAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getCookies")).and(takesArguments(0)),
        CLASS_NAME + "$GetCookiesAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getQueryString")).and(takesArguments(0)),
        CLASS_NAME + "$GetQueryStringAdvice");
    transformer.applyAdvice(
        isMethod().and(namedOneOf("getInputStream", "getReader")).and(takesArguments(0)),
        CLASS_NAME + "$GetBodyAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getRequestDispatcher")).and(takesArguments(String.class)),
        CLASS_NAME + "$GetRequestDispatcherAdvice");
  }

  public static class GetHeaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onExit(
        @Advice.Argument(0) final String name, @Advice.Return final String value) {
      if (value == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      module.taint(value, SourceTypes.REQUEST_HEADER_VALUE, name);
    }
  }

  public static class GetHeadersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onExit(
        @Advice.Argument(0) final String name,
        @Advice.Return(readOnly = false) Enumeration<String> enumeration) {
      if (enumeration == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      enumeration =
          TaintableEnumeration.wrap(enumeration, module, SourceTypes.REQUEST_HEADER_VALUE, name);
    }
  }

  public static class GetHeaderNamesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_NAME)
    public static void onExit(@Advice.Return(readOnly = false) Enumeration<String> enumeration) {
      if (enumeration == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      enumeration =
          TaintableEnumeration.wrap(enumeration, module, SourceTypes.REQUEST_HEADER_NAME, true);
    }
  }

  public static class GetParameterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Argument(0) final String name, @Advice.Return final String value) {
      if (value == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      module.taint(value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
    }
  }

  public static class GetParameterValuesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Argument(0) final String name, @Advice.Return final String[] values) {
      if (values == null || values.length == 0) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      final IastContext ctx = IastContext.Provider.get();
      for (final String value : values) {
        module.taint(ctx, value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
      }
    }
  }

  public static class GetParameterMapAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(@Advice.Return final Map<String, String[]> parameters) {
      if (parameters == null || parameters.isEmpty()) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      final IastContext ctx = IastContext.Provider.get();
      for (final Map.Entry<String, String[]> entry : parameters.entrySet()) {
        final String name = entry.getKey();
        module.taint(ctx, name, SourceTypes.REQUEST_PARAMETER_NAME, name);
        final String[] values = entry.getValue();
        if (values != null) {
          for (final String value : entry.getValue()) {
            module.taint(ctx, value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
          }
        }
      }
    }
  }

  public static class GetParameterNamesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_NAME)
    public static void onExit(@Advice.Return(readOnly = false) Enumeration<String> enumeration) {
      if (enumeration == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      enumeration =
          TaintableEnumeration.wrap(enumeration, module, SourceTypes.REQUEST_PARAMETER_NAME, true);
    }
  }

  public static class GetCookiesAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void onExit(@Advice.Return final Cookie[] cookies) {
      if (cookies == null || cookies.length == 0) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      final IastContext ctx = IastContext.Provider.get();
      for (final Cookie cookie : cookies) {
        module.taint(ctx, cookie, SourceTypes.REQUEST_COOKIE_VALUE);
      }
    }
  }

  public static class GetQueryStringAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_QUERY)
    public static void onExit(@Advice.Return final String queryString) {
      if (queryString == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      module.taint(queryString, SourceTypes.REQUEST_QUERY);
    }
  }

  public static class GetBodyAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_BODY)
    public static void onExit(@Advice.Return final Object body) {
      if (body == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      module.taint(body, SourceTypes.REQUEST_BODY);
    }
  }

  public static class GetRequestDispatcherAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.UNVALIDATED_REDIRECT)
    public static void onExit(@Advice.Argument(0) final String path) {
      if (path == null) {
        return;
      }
      final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
      if (module == null) {
        return;
      }
      module.onRedirect(path);
    }
  }
}
