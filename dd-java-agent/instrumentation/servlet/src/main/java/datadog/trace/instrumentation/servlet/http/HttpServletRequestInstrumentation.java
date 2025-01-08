package datadog.trace.instrumentation.servlet.http;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.iast.TaintableEnumeration;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import java.util.Enumeration;
import java.util.Map;
import javax.servlet.http.Cookie;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public class HttpServletRequestInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final String CLASS_NAME = HttpServletRequestInstrumentation.class.getName();
  private static final ElementMatcher.Junction<? super TypeDescription> WRAPPER_CLASS =
      named("javax.servlet.http.HttpServletRequestWrapper");

  public HttpServletRequestInstrumentation() {
    super("servlet", "servlet-request");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServletRequest";
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

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetHeaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onExit(
        @Advice.Argument(0) final String name,
        @Advice.Return final String value,
        @ActiveRequestContext RequestContext reqCtx) {
      if (value == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      module.taintString(ctx, value, SourceTypes.REQUEST_HEADER_VALUE, name);
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetHeadersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onExit(
        @Advice.Argument(0) final String name,
        @Advice.Return(readOnly = false) Enumeration<String> enumeration,
        @ActiveRequestContext RequestContext reqCtx) {
      if (enumeration == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      enumeration =
          TaintableEnumeration.wrap(
              ctx, enumeration, module, SourceTypes.REQUEST_HEADER_VALUE, name);
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetHeaderNamesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_NAME)
    public static void onExit(
        @Advice.Return(readOnly = false) Enumeration<String> enumeration,
        @ActiveRequestContext RequestContext reqCtx) {
      if (enumeration == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      enumeration =
          TaintableEnumeration.wrap(
              ctx, enumeration, module, SourceTypes.REQUEST_HEADER_NAME, true);
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetParameterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Argument(0) final String name,
        @Advice.Return final String value,
        @ActiveRequestContext RequestContext reqCtx) {
      if (value == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      module.taintString(ctx, value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetParameterValuesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Argument(0) final String name,
        @Advice.Return final String[] values,
        @ActiveRequestContext RequestContext reqCtx) {
      if (values == null || values.length == 0) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      for (final String value : values) {
        module.taintString(ctx, value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetParameterMapAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Return final Map<String, String[]> parameters,
        @ActiveRequestContext RequestContext reqCtx) {
      if (parameters == null || parameters.isEmpty()) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      for (final Map.Entry<String, String[]> entry : parameters.entrySet()) {
        final String name = entry.getKey();
        module.taintString(ctx, name, SourceTypes.REQUEST_PARAMETER_NAME, name);
        final String[] values = entry.getValue();
        if (values != null) {
          for (final String value : entry.getValue()) {
            module.taintString(ctx, value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
          }
        }
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetParameterNamesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_NAME)
    public static void onExit(
        @Advice.Return(readOnly = false) Enumeration<String> enumeration,
        @ActiveRequestContext RequestContext reqCtx) {
      if (enumeration == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      enumeration =
          TaintableEnumeration.wrap(
              ctx, enumeration, module, SourceTypes.REQUEST_PARAMETER_NAME, true);
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetCookiesAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void onExit(
        @Advice.Return final Cookie[] cookies, @ActiveRequestContext RequestContext reqCtx) {
      if (cookies == null || cookies.length == 0) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      for (final Cookie cookie : cookies) {
        module.taintObject(ctx, cookie, SourceTypes.REQUEST_COOKIE_VALUE);
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetQueryStringAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_QUERY)
    public static void onExit(
        @Advice.Return final String queryString, @ActiveRequestContext RequestContext reqCtx) {
      if (queryString == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      module.taintString(ctx, queryString, SourceTypes.REQUEST_QUERY);
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetBodyAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_BODY)
    public static void onExit(
        @Advice.Return final Object body, @ActiveRequestContext RequestContext reqCtx) {
      if (body == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      module.taintObject(ctx, body, SourceTypes.REQUEST_BODY);
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
