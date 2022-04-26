package datadog.trace.instrumentation.jakarta3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.PathSegment;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This adds the filter class name to the request properties. The class name is used by <code>
 * DefaultRequestContextInstrumentation</code>
 */
@AutoService(Instrumenter.class)
public class ContainerRequestFilterInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public ContainerRequestFilterInstrumentation() {
    super("jakarta-rs", "jakartars", "jakarta-rs-filter");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("jakarta.ws.rs.container.ContainerRequestFilter");
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("jakarta.ws.rs.container.ContainerRequestFilter"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("filter"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("jakarta.ws.rs.container.ContainerRequestContext"))),
        ContainerRequestFilterInstrumentation.class.getName() + "$RequestFilterAdvice");
  }

  public static class RequestFilterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void setFilterClass(
        @Advice.This final ContainerRequestFilter filter,
        @Advice.Argument(0) final ContainerRequestContext context) {
      context.setProperty(JakartaRsAnnotationsDecorator.ABORT_FILTER_CLASS, filter.getClass());
    }

    private void muzzleCheck(@PathParam("foo") PathSegment foo) {}
  }
}
