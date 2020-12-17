package datadog.trace.instrumentation.jetty70;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.http.Generator;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Response;

@AutoService(Instrumenter.class)
public final class JettyGeneratorInstrumentation extends Instrumenter.Tracing {

  public JettyGeneratorInstrumentation() {
    super("jetty");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("org.eclipse.jetty.http.Generator"));
  }

  @Override
  public Map<String, String> contextStore() {
    // The lifecycle of these objects are aligned, and are recycled by jetty, minimizing leak risk.
    return singletonMap("org.eclipse.jetty.http.Generator", "org.eclipse.jetty.server.Response");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("setResponse").and(takesArgument(0, int.class)),
        JettyGeneratorInstrumentation.class.getName() + "$SetResponseAdvice");
  }

  // This advice ensures that the right status is updated on the response.
  public static class SetResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void updateResponse(
        @Advice.This final Generator generator, @Advice.Argument(0) final int status) {
      Response response =
          InstrumentationContext.get(Generator.class, Response.class).get(generator);
      if (response != null) {
        response.setStatus(status);
      }
    }

    private void muzzleCheck(HttpConnection connection) {
      connection.getGenerator();
    }
  }
}
