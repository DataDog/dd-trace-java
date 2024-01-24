package datadog.trace.instrumentation.servlet3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
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

/**
 * Request bodies before servlet 3.1.x are covered by Servlet2RequestBodyInstrumentation from the
 * "request-2" module. Any changes to the behaviour here should also be reflected in "request-2".
 */
@AutoService(Instrumenter.class)
public class Servlet31RequestBodyInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForTypeHierarchy {
  public Servlet31RequestBodyInstrumentation() {
    super("servlet-request-body");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-3.1.x";
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Avoid matching request bodies before 3.1.x which have their own instrumentation
    return hasClassNamed("javax.servlet.ReadListener");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServletRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        // ignore wrappers that ship with servlet-api
        .and(namedNoneOf("javax.servlet.http.HttpServletRequestWrapper"))
        .and(not(extendsClass(named("javax.servlet.http.HttpServletRequestWrapper"))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getInputStream")
            .and(takesNoArguments())
            .and(returns(named("javax.servlet.ServletInputStream")))
            .and(isPublic()),
        packageName + ".HttpServletGetInputStreamAdvice");
    transformer.applyAdvice(
        named("getReader")
            .and(takesNoArguments())
            .and(returns(named("java.io.BufferedReader")))
            .and(isPublic()),
        packageName + ".HttpServletGetReaderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.servlet.BufferedReaderWrapper",
      "datadog.trace.instrumentation.servlet.AbstractServletInputStreamWrapper",
      "datadog.trace.instrumentation.servlet3.Servlet31InputStreamWrapper"
    };
  }
}
