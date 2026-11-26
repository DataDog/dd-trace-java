package datadog.trace.instrumentation.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.appsec.route.NormalizedRouteHelper;
import net.bytebuddy.asm.Advice;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.ResteasyUriInfo;

/**
 * Captures the base URI and the bound path parameters before RESTEasy invokes the matched resource
 * method, for {@code _dd.appsec.normalized_route}. {@code ResteasyUriInfo.getPathParameters} is not
 * hooked by AppSec (its hook is {@link MethodExpressionInstrumentation}).
 */
@AutoService(InstrumenterModule.class)
public class ResourceMethodInvokerInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ResourceMethodInvokerInstrumentation() {
    super("resteasy");
  }

  @Override
  public String muzzleDirective() {
    return "jaxrs";
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.resteasy.core.ResourceMethodInvoker";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("invoke")
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.jboss.resteasy.spi.HttpRequest")))
            .and(takesArgument(1, named("org.jboss.resteasy.spi.HttpResponse")))
            .and(takesArgument(2, Object.class)),
        ResourceMethodInvokerInstrumentation.class.getName() + "$InvokeAdvice");
  }

  public static class InvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void before(@Advice.Argument(0) final HttpRequest request) {
      if (!NormalizedRouteHelper.isEnabled()) {
        return;
      }
      ResteasyUriInfo uriInfo = request.getUri();
      if (uriInfo != null) {
        // encoded values, like Jersey's encodedTemplateValues
        JaxRsRequestMatchCapture.capture(uriInfo.getBaseUri(), uriInfo.getPathParameters(false));
      }
    }
  }
}
