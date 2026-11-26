package datadog.trace.instrumentation.jersey2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.appsec.route.NormalizedRouteHelper;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.glassfish.jersey.server.internal.routing.UriRoutingContext;

/**
 * Captures the base URI and the bound path parameters once Jersey (2 and 3) has matched the
 * resource method, for {@code _dd.appsec.normalized_route}. Reads the {@code encodedTemplateValues}
 * field rather than calling {@code getPathParameters}, which {@link
 * UriRoutingContextInstrumentation} hooks to run the WAF.
 */
@AutoService(InstrumenterModule.class)
public class UriRoutingContextMatchInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public UriRoutingContextMatchInstrumentation() {
    super("jersey");
  }

  @Override
  public String muzzleDirective() {
    return "jersey_server_2+3";
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.server.internal.routing.UriRoutingContext";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setMatchedResourceMethod").and(takesArguments(1)),
        getClass().getName() + "$SetMatchedResourceMethodAdvice");
  }

  public static class SetMatchedResourceMethodAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This final UriRoutingContext thiz,
        @Advice.FieldValue("encodedTemplateValues") final Map<String, ?> encodedTemplateValues) {
      if (!NormalizedRouteHelper.isEnabled()) {
        return;
      }
      // The private field encodedTemplateValues is read because getPathParameters runs the WAF.
      // It is stable from Jersey 2.0 to 4.0 (checked with javap), but muzzle does not validate
      // field names: recheck it when a new Jersey major is supported.
      // It is a javax (2.x) or jakarta (3.x+) MultivaluedHashMap: read it as a Map.
      JaxRsRequestMatchCapture.capture(thiz.getBaseUri(), encodedTemplateValues);
    }
  }
}
