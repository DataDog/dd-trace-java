package controllers;

import actions.Action1;
import actions.Action2;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import javax.inject.Inject;
import play.Configuration;
import play.libs.F.Promise;
import play.libs.ws.WSClient;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.With;

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
  public Promise<Result> doGet(final Integer id) {
    Tracer tracer = GlobalTracer.get();
    Span span = tracer.buildSpan("do-get").start();
    Scope scope = tracer.scopeManager().activate(span);
    try {
      if (id > 0) {
        return ws.url(clientRequestBase + id)
            .get()
            .map(response -> status(response.getStatus(), "J Got '" + response.getBody() + "'"));
      } else {
        return Promise.pure(badRequest("No ID."));
      }
    } finally {
      scope.close();
      span.finish();
    }
  }
}
