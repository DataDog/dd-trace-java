package datadog.trace.instrumentation.servlet.request;

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
public final class HttpServletRequestIastInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {
  public HttpServletRequestIastInstrumentation() {
    super("servlet", "servlet-request");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServletRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        // Instrumenting wrappers is, most likely, redundant.
        .and(not(extendsClass(named("javax.servlet.http.HttpServletRequestWrapper"))));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TaintEnumerable",
      packageName + ".TaintEnumerable$ParameterNamesEnumerable",
      packageName + ".TaintEnumerable$HeaderValuesEnumerable",
      packageName + ".TaintEnumerable$HeaderNamesEnumerable"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getQueryString").and(takesArguments(0)), packageName + ".GetQueryStringAdvice");
    transformation.applyAdvice(
        named("getParameter").and(takesArguments(String.class)),
        packageName + ".GetParameterAdvice");
    transformation.applyAdvice(
        named("getParameterNames").and(takesArguments(0)),
        packageName + ".GetParameterNamesAdvice");
    transformation.applyAdvice(
        named("getParameterValues").and(takesArguments(String.class)),
        packageName + ".GetParameterValuesAdvice");
    transformation.applyAdvice(
        named("getHeader").and(takesArguments(String.class)), packageName + ".GetHeaderAdvice");
    transformation.applyAdvice(
        named("getHeaders").and(takesArguments(String.class)), packageName + ".GetHeadersAdvice");
    transformation.applyAdvice(
        named("getHeaderNames").and(takesArguments(0)), packageName + ".GetHeaderNamesAdvice");
    transformation.applyAdvice(
        named("getCookies").and(takesArguments(0)), packageName + ".GetCookiesAdvice");
    transformation.applyAdvice(
        named("getInputStream").and(takesArguments(0)), packageName + ".GetInputStreamAdvice");
    transformation.applyAdvice(
        named("getReader").and(takesArguments(0)), packageName + ".GetInputStreamAdvice");
  }
}
