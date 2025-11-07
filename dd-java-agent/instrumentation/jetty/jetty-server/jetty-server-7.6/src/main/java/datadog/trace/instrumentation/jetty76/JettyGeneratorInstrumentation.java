package datadog.trace.instrumentation.jetty76;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.http.AbstractGenerator;
import org.eclipse.jetty.http.Generator;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Response;

@AutoService(InstrumenterModule.class)
public final class JettyGeneratorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JettyGeneratorInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.http.AbstractGenerator";
  }

  @Override
  public Map<String, String> contextStore() {
    // The lifecycle of these objects are aligned, and are recycled by jetty, minimizing leak risk.
    return singletonMap("org.eclipse.jetty.http.Generator", "org.eclipse.jetty.server.Response");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setResponse").and(takesArgument(0, int.class)),
        JettyGeneratorInstrumentation.class.getName() + "$SetResponseAdvice");
  }

  /**
   * The generator is what writes out the final bytes that are sent back to the requestor. We read
   * the status code from the response in ResetAdvice, but in some cases the final status code is
   * only set in the generator directly, not the response. (For example, this happens when an
   * exception is thrown and jetty must send a 500 status.) We use this instrumentation to ensure
   * that the response is updated when the generator is. Since the status on the response is reset
   * when the connection is reset, this minor change in behavior is inconsequential.
   */
  public static class SetResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void updateResponse(
        @Advice.This final AbstractGenerator generator, @Advice.Argument(0) final int status) {
      Response response =
          InstrumentationContext.get(Generator.class, Response.class).get(generator);
      if (response != null) {
        response.setStatus(status);
      }
    }

    private void muzzleCheck(AbstractHttpConnection connection) {
      connection.getGenerator();
    }
  }
}
