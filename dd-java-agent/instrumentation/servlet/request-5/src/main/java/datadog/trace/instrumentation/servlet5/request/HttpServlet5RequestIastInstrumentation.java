package datadog.trace.instrumentation.servlet5.request;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class HttpServlet5RequestIastInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {

  // private static final String commonPackageName =
  // "datadog.trace.instrumentation.servlet.request";
  private final String commonPackageName = packageName;

  public HttpServlet5RequestIastInstrumentation() {
    super("servlet", "servlet-request");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.http.HttpServletRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        // Instrumenting wrappers is, most likely, redundant.
        .and(not(extendsClass(named("jakarta.servlet.http.HttpServletRequestWrapper"))));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      commonPackageName + ".TaintEnumerable",
      commonPackageName + ".TaintEnumerable$ParameterNamesEnumerable",
      commonPackageName + ".TaintEnumerable$HeaderValuesEnumerable",
      commonPackageName + ".TaintEnumerable$HeaderNamesEnumerable"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getQueryString").and(takesArguments(0)),
        commonPackageName + ".GetQueryStringAdvice");
    transformation.applyAdvice(
        named("getParameter").and(takesArguments(String.class)),
        commonPackageName + ".GetParameterAdvice");
    transformation.applyAdvice(
        named("getParameterNames").and(takesArguments(0)),
        commonPackageName + ".GetParameterNamesAdvice");
    transformation.applyAdvice(
        named("getParameterValues").and(takesArguments(String.class)),
        commonPackageName + ".GetParameterValuesAdvice");
    transformation.applyAdvice(
        named("getHeader").and(takesArguments(String.class)),
        commonPackageName + ".GetHeaderAdvice");
    transformation.applyAdvice(
        named("getHeaders").and(takesArguments(String.class)),
        commonPackageName + ".GetHeadersAdvice");
    transformation.applyAdvice(
        named("getHeaderNames").and(takesArguments(0)),
        commonPackageName + ".GetHeaderNamesAdvice");
    transformation.applyAdvice(
        named("getCookies").and(takesArguments(0)), commonPackageName + ".GetCookiesAdvice");
    transformation.applyAdvice(
        named("getInputStream").and(takesArguments(0)),
        commonPackageName + ".GetInputStreamAdvice");
    transformation.applyAdvice(
        named("getReader").and(takesArguments(0)), commonPackageName + ".GetInputStreamAdvice");
  }
}
