package datadog.trace.instrumentation.http_url_connection;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.UrlConnectionDecorator.DECORATE;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.URL;
import java.net.URLStreamHandler;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class UrlInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public UrlInstrumentation() {
    super("urlconnection", "httpurlconnection");
  }

  @Override
  public String instrumentedType() {
    return "java.net.URL";
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
        String protocol = url.getProtocol();
        protocol = protocol != null ? protocol : "url";

        final AgentSpan span = startSpan(DECORATE.operationName(protocol));

        try (final AgentScope scope = activateSpan(span)) {
          DECORATE.afterStart(span);
          DECORATE.onURL(span, url);
          HTTP_RESOURCE_DECORATOR.withClientPath(span, null, url.getPath());

          span.setError(true);
          span.addThrowable(throwable);
          span.finish();
        }
      }
    }
  }
}
