import static net.bytebuddy.matcher.ElementMatchers.named;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.japi.function.Function;
import akka.stream.scaladsl.BidiFlow;
import com.google.auto.service.AutoService;
import datadog.trace.agent.test.base.HttpServerTestAdvice;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.api.AgentScope;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class AkkaHttpTestInstrumentation implements Instrumenter {

  @Override
  public AgentBuilder instrument(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(named("akka.http.impl.engine.server.HttpServerBluePrint$"))
        .transform(
            new AgentBuilder.Transformer.ForAdvice()
                .advice(named("requestTimeoutSupport"), AkkaServerTestAdvice.class.getName()));
  }

  public static class AkkaServerTestAdvice {
    @Advice.OnMethodExit
    public static void methodExit(
        @Advice.Return(readOnly = false)
            BidiFlow<HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed> response) {
      final BidiFlowWrapper wrapper = new BidiFlowWrapper();
      final BidiFlow<HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed> testSpanFlow =
          akka.stream.javadsl.BidiFlow.fromFunctions(
                  wrapper.responseFunction, wrapper.requestFunction)
              .asScala();
      response = testSpanFlow.atop(response);
    }

    public static class BidiFlowWrapper {
      public final Function<HttpRequest, HttpRequest> requestFunction;
      public final Function<HttpResponse, HttpResponse> responseFunction;
      final Queue<AgentScope> agentScopes = new LinkedBlockingQueue<>();

      public BidiFlowWrapper() {
        requestFunction = new HttpRequestFunction();
        responseFunction = new HttpResponseFunction();
      }

      public class HttpRequestFunction implements Function<HttpRequest, HttpRequest> {
        @Override
        public HttpRequest apply(final HttpRequest httpRequest) throws Exception {
          final AgentScope scope = HttpServerTestAdvice.ServerEntryAdvice.methodEnter();
          if (scope != null) {
            agentScopes.add(scope);
          }
          return httpRequest;
        }
      }

      public class HttpResponseFunction implements Function<HttpResponse, HttpResponse> {
        @Override
        public HttpResponse apply(final HttpResponse httpResponse) throws Exception {
          HttpServerTestAdvice.ServerEntryAdvice.methodExit(agentScopes.poll());
          return httpResponse;
        }
      }
    }
  }
}
