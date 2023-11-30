package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class Servlet5RequestBodyInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForTypeHierarchy {
  public Servlet5RequestBodyInstrumentation() {
    super("servlet-request-body");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.http.HttpServletRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        // ignore wrappers that ship with servlet-api
        .and(namedNoneOf("jakarta.servlet.http.HttpServletRequestWrapper"))
        .and(not(extendsClass(named("jakarta.servlet.http.HttpServletRequestWrapper"))));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getInputStream")
            .and(takesNoArguments())
            .and(returns(named("jakarta.servlet.ServletInputStream")))
            .and(isPublic()),
        packageName + ".HttpServletGetInputStreamAdvice");
    transformation.applyAdvice(
        named("getReader")
            .and(takesNoArguments())
            .and(returns(named("java.io.BufferedReader")))
            .and(isPublic()),
        packageName + ".HttpServletGetReaderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.servlet5.BufferedReaderWrapper",
      "datadog.trace.instrumentation.servlet5.AbstractServletInputStreamWrapper",
      "datadog.trace.instrumentation.servlet5.Servlet31InputStreamWrapper"
    };
  }
}
