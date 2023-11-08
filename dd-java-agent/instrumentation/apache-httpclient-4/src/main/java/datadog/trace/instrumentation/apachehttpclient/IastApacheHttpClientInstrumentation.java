package datadog.trace.instrumentation.apachehttpclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.SsrfModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;

@AutoService(Instrumenter.class)
public class IastApacheHttpClientInstrumentation extends Instrumenter.Iast
    implements Instrumenter.CanShortcutTypeMatching {

  public IastApacheHttpClientInstrumentation() {
    super("httpclient", "apache-httpclient", "apache-http-client");
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.http.impl.client.AbstractHttpClient",
      "software.amazon.awssdk.http.apache.internal.impl.ApacheSdkHttpClient",
      "org.apache.http.impl.client.AutoRetryHttpClient",
      "org.apache.http.impl.client.CloseableHttpClient",
      "org.apache.http.impl.client.ContentEncodingHttpClient",
      "org.apache.http.impl.client.DecompressingHttpClient",
      "org.apache.http.impl.client.DefaultHttpClient",
      "org.apache.http.impl.client.InternalHttpClient",
      "org.apache.http.impl.client.MinimalHttpClient",
      "org.apache.http.impl.client.SystemDefaultHttpClient",
      "com.netflix.http4.NFHttpClient",
      "com.amazonaws.http.apache.client.impl.SdkHttpClient"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.http.client.HttpClient";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.apache.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.http.HttpRequest")))
            .and(takesArgument(2, named("org.apache.http.protocol.HttpContext"))),
        IastApacheHttpClientInstrumentation.class.getName() + "$ExecuteAdvice");
  }

  public static class ExecuteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.SSRF)
    public static void methodEnter(@Advice.Argument(0) final HttpHost host) {
      final SsrfModule module = InstrumentationBridge.SSRF;
      if (module != null) {
        module.onURLConnection(host);
      }
    }

    private static void muzzleCheck() {
      HttpClient client = new DefaultHttpClient();
    }
  }
}
