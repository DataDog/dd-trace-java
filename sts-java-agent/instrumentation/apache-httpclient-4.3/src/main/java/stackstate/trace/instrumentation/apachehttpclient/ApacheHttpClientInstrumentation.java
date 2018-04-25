package stackstate.trace.instrumentation.apachehttpclient;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;

import com.google.auto.service.AutoService;
import io.opentracing.util.GlobalTracer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.execchain.ClientExecChain;
import stackstate.trace.agent.tooling.HelperInjector;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.agent.tooling.STSAdvice;
import stackstate.trace.agent.tooling.STSTransformers;

@AutoService(Instrumenter.class)
public class ApacheHttpClientInstrumentation extends Instrumenter.Configurable {

  public ApacheHttpClientInstrumentation() {
    super("httpclient");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            named("org.apache.http.impl.client.HttpClientBuilder"),
            classLoaderHasClasses(
                "org.apache.http.HttpException",
                "org.apache.http.HttpRequest",
                "org.apache.http.client.RedirectStrategy",
                "org.apache.http.client.methods.CloseableHttpResponse",
                "org.apache.http.client.methods.HttpExecutionAware",
                "org.apache.http.client.methods.HttpRequestWrapper",
                "org.apache.http.client.protocol.HttpClientContext",
                "org.apache.http.conn.routing.HttpRoute",
                "org.apache.http.impl.execchain.ClientExecChain"))
        .transform(
            new HelperInjector(
                "stackstate.trace.instrumentation.apachehttpclient.STSTracingClientExec",
                "stackstate.trace.instrumentation.apachehttpclient.STSTracingClientExec$HttpHeadersInjectAdapter"))
        .transform(STSTransformers.defaultTransformers())
        .transform(
            STSAdvice.create()
                .advice(
                    isMethod().and(named("decorateProtocolExec")),
                    ApacheHttpClientAdvice.class.getName()))
        .asDecorator();
  }

  public static class ApacheHttpClientAdvice {
    /** Strategy: add our tracing exec to the apache exec chain. */
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addTracingExec(@Advice.Return(readOnly = false) ClientExecChain execChain) {
      execChain =
          new STSTracingClientExec(
              execChain, DefaultRedirectStrategy.INSTANCE, false, GlobalTracer.get());
    }
  }
}
