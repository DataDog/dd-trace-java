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
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Response bodies before servlet 3.1.x are covered by Servlet2ResponseBodyInstrumentation from the
 * "request-2" module. Any changes to the behaviour here should also be reflected in "request-2".
 */
@AutoService(InstrumenterModule.class)
public class Servlet31ResponseBodyInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public Servlet31ResponseBodyInstrumentation() {
    super("servlet-response-body");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-3.1.x";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Avoid matching response bodies before 3.1.x which have their own instrumentation
    return hasClassNamed("javax.servlet.WriteListener");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServletResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        // ignore wrappers that ship with servlet-api
        .and(namedNoneOf("javax.servlet.http.HttpServletResponseWrapper"))
        .and(not(extendsClass(named("javax.servlet.http.HttpServletResponseWrapper"))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getOutputStream")
            .and(takesNoArguments())
            .and(returns(named("javax.servlet.ServletOutputStream")))
            .and(isPublic()),
        packageName + ".HttpServletGetOutputStreamAdvice");
    transformer.applyAdvice(
        named("getWriter")
            .and(takesNoArguments())
            .and(returns(named("java.io.PrintWriter")))
            .and(isPublic()),
        packageName + ".HttpServletGetWriterAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.servlet.BufferedWriterWrapper",
      "datadog.trace.instrumentation.servlet.AbstractServletOutputStreamWrapper",
      "datadog.trace.instrumentation.servlet3.Servlet31OutputStreamWrapper"
    };
  }
}
