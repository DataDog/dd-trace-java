package controllers;

import actions.*;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.inject.Inject;
import play.api.ConfigLoader;
import play.api.Configuration;
import play.api.mvc.ControllerComponents;
import play.libs.ws.*;
import play.mvc.*;

public class JController extends Controller {

  private final WSClient ws;
  private final String clientRequestBase;

  @Inject
  public JController(WSClient ws, Configuration configuration, ControllerComponents c) {
    this.ws = ws;
    this.clientRequestBase =
        configuration
            .getOptional("client.request.base", ConfigLoader.stringLoader())
            .getOrElse(() -> "http://localhost:0/broken/");
  }

  @With({Action1.class, Action2.class})
  public CompletionStage<Result> doGet(final Integer id) {
    Tracer tracer = GlobalOpenTelemetry.getTracer("play-test");
    Span span = tracer.spanBuilder("do-get").startSpan();
    Scope scope = span.makeCurrent();
    try {
      if (id > 0) {
        return ws.url(clientRequestBase + id)
            .get()
            .thenApply(
                response -> status(response.getStatus(), "J Got '" + response.getBody() + "'"));
      } else {
        return CompletableFuture.supplyAsync(() -> badRequest("No ID."));
      }
    } finally {
      scope.close();
      span.end();
    }
  }
}
