package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.pekkohttp.PekkoHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.pekkohttp.PekkoHttpClientDecorator.PEKKO_CLIENT_REQUEST;
import static datadog.trace.instrumentation.pekkohttp.PekkoHttpClientHelpers.OnCompleteHandler;
import static datadog.trace.instrumentation.pekkohttp.PekkoHttpClientHelpers.PekkoHttpHeaders;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import net.bytebuddy.asm.Advice;
import org.apache.pekko.http.scaladsl.HttpExt;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;
import scala.concurrent.Future;

@AutoService(Instrumenter.class)
public final class PekkoHttpSingleRequestInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public PekkoHttpSingleRequestInstrumentation() {
    super("pekko-http", "pekko-http-client");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.pekko.http.scaladsl.HttpExt";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PekkoHttpClientHelpers",
      packageName + ".PekkoHttpClientHelpers$OnCompleteHandler",
      packageName + ".PekkoHttpClientHelpers$PekkoHttpHeaders",
      packageName + ".PekkoHttpClientHelpers$HasSpanHeader",
      packageName + ".PekkoHttpClientDecorator",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // This is mainly for compatibility with 10.0
    transformation.applyAdvice(
        named("singleRequest")
            .and(takesArgument(0, named("org.apache.pekko.http.scaladsl.model.HttpRequest"))),
        PekkoHttpSingleRequestInstrumentation.class.getName() + "$SingleRequestAdvice");
    // This is for 10.1+
    transformation.applyAdvice(
        named("singleRequestImpl")
            .and(takesArgument(0, named("org.apache.pekko.http.scaladsl.model.HttpRequest"))),
        PekkoHttpSingleRequestInstrumentation.class.getName() + "$SingleRequestAdvice");
  }

  public static class SingleRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(value = 0, readOnly = false) HttpRequest request) {
      /*
      Versions 10.0 and 10.1 have slightly different structure that is hard to distinguish so here
      we cast 'wider net' and avoid instrumenting twice.
      In the future we may want to separate these, but since lots of code is reused we would need to come up
      with way of continuing to reusing it.
       */
      final PekkoHttpHeaders headers = new PekkoHttpHeaders(request);
      if (headers.hadSpan()) {
        return null;
      }

      final AgentSpan span = startSpan(PEKKO_CLIENT_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);

      if (request != null) {
        propagate().inject(span, request, headers);
        propagate()
            .injectPathwayContext(
                span, request, headers, HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS);
        // Request is immutable, so we have to assign new value once we update headers
        request = headers.getRequest();
      }
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.This final HttpExt thiz,
        @Advice.Return final Future<HttpResponse> responseFuture,
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      final AgentSpan span = scope.span();

      if (throwable == null) {
        responseFuture.onComplete(new OnCompleteHandler(span), thiz.system().dispatcher());
      } else {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
      }
      scope.close();
    }
  }
}
