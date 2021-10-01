package datadog.trace.instrumentation.http_url_connection;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.cache.RadixTreeCache.PORTS;
import static datadog.trace.api.cache.RadixTreeCache.UNSET_PORT;
import static datadog.trace.api.http.UrlBasedResourceNameCalculator.SIMPLE_PATH_NORMALIZER;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InternalJarURLHandler;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.net.URL;
import java.net.URLStreamHandler;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class UrlInstrumentation extends Instrumenter.Tracing {

  public static final String COMPONENT = "UrlConnection";

  public UrlInstrumentation() {
    super("urlconnection", "httpurlconnection");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("java.net.URL");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("openConnection")),
        UrlInstrumentation.class.getName() + "$ConnectionErrorAdvice");
  }

  public static class ConnectionErrorAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void errorSpan(
        @Advice.This final URL url,
        @Advice.Thrown final Throwable throwable,
        @Advice.FieldValue("handler") final URLStreamHandler handler) {
      if (throwable != null) {
        // Various agent components end up calling `openConnection` indirectly
        // when loading classes. Avoid tracing these calls.
        final boolean disableTracing = handler instanceof InternalJarURLHandler;
        if (disableTracing) {
          return;
        }

        String protocol = url.getProtocol();
        protocol = protocol != null ? protocol : "url";

        final AgentSpan span =
            startSpan(protocol + ".request")
                .setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT)
                .setSpanType(InternalSpanTypes.HTTP_CLIENT)
                .setTag(Tags.COMPONENT, COMPONENT);

        try (final AgentScope scope = activateSpan(span)) {
          span.setTag(Tags.HTTP_URL, url.toString());
          span.setTag(
              Tags.PEER_PORT,
              url.getPort() > UNSET_PORT ? PORTS.get(url.getPort()) : PORTS.get(80));
          String host = url.getHost();
          span.setTag(Tags.PEER_HOSTNAME, host);
          if (Config.get().isHttpClientSplitByDomain() && null != host && !host.isEmpty()) {
            span.setServiceName(host);
          }
          span.setResourceName(
              SIMPLE_PATH_NORMALIZER.normalize(url.getPath()),
              ResourceNamePriorities.HTTP_PATH_NORMALIZER);

          span.setError(true);
          span.addThrowable(throwable);
          span.finish();
        }
      }
    }
  }
}
