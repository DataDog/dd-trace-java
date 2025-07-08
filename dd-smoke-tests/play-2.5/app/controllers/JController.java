package controllers;

import actions.*;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.inject.Inject;
import play.Configuration;
import play.libs.ws.*;
import play.mvc.*;

public class JController extends Controller {

  private final WSClient ws;
  private final String clientRequestBase;

  @Inject
  public JController(WSClient ws, Configuration configuration) {
    this.ws = ws;
    this.clientRequestBase =
        configuration.getString("client.request.base", "http://localhost:0/broken/");
  }

  @With({Action1.class, Action2.class})
  public CompletionStage<Result> doGet(final Integer id) {
    Tracer tracer = GlobalTracer.get();
    Span span = tracer.buildSpan("do-get").start();
    Scope scope = tracer.scopeManager().activate(span);
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
      span.finish();
    }
  }
}
