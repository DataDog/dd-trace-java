package datadog.smoketest.vertx_3_4;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.RoutingContext;
import java.util.Arrays;
import java.util.Optional;

public enum IastHandler implements Handler<RoutingContext> {
  HEADER("/header") {
    @Override
    public void handle(final RoutingContext rc) {
      final String value = rc.request().getHeader("header");
      rc.response().end("Received " + value);
    }
  },
  HEADERS("/headers") {
    @Override
    public void handle(final RoutingContext rc) {
      final MultiMap value = rc.request().headers();
      rc.response().end("Received " + value.get("header"));
    }
  },
  PARAM("/param") {
    @Override
    public void handle(final RoutingContext rc) {
      final String value = rc.request().getParam("param");
      rc.response().end("Received " + value);
    }
  },
  PARAMS("/params") {
    @Override
    public void handle(final RoutingContext rc) {
      final MultiMap value = rc.request().params();
      rc.response().end("Received " + value.get("param"));
    }
  },
  FORM_ATTRIBUTE("/form_attribute") {
    @Override
    public void handle(final RoutingContext rc) {
      final String value = rc.request().getFormAttribute("formAttribute");
      rc.response().end("Received " + value);
    }
  },
  PATH_PARAM("/path/:name") {
    @Override
    public void handle(final RoutingContext rc) {
      final String value = rc.pathParam("name");
      rc.response().end("Received " + value);
    }
  },
  COOKIE("/cookie") {
    @Override
    public void handle(final RoutingContext rc) {
      final Cookie cookie = rc.getCookie("name");
      rc.response().end("Received " + cookie.getName() + " " + cookie.getValue());
    }
  },
  BODY_STRING("/body/string") {
    @Override
    public void handle(final RoutingContext rc) {
      final String encoding = rc.request().getParam("encoding");
      if (encoding != null) {
        rc.response().end("Received " + rc.getBodyAsString(encoding));
      } else {
        rc.response().end("Received " + rc.getBodyAsString());
      }
    }
  },
  BODY_JSON("/body/json") {
    @Override
    public void handle(final RoutingContext rc) {
      rc.response().end("Received " + rc.getBodyAsJson());
    }
  },
  BODY_JSON_ARRAY("/body/jsonArray") {
    @Override
    public void handle(final RoutingContext rc) {
      rc.response().end("Received " + rc.getBodyAsJsonArray());
    }
  },
  EVENT_BUS("/eventBus") {

    @Override
    public void init(final Vertx vertx) {
      vertx
          .eventBus()
          .consumer(
              name(),
              message -> {
                final JsonObject payload = (JsonObject) message.body();
                final String response = payload.getString("name").toUpperCase();
                message.reply(response);
              });
    }

    @Override
    public void handle(final RoutingContext rc) {
      final JsonObject target = rc.getBodyAsJson();
      rc.vertx()
          .eventBus()
          .send(
              name(),
              target,
              reply -> {
                if (reply.succeeded()) {
                  rc.response().end("Received " + reply.result().body());
                } else {
                  rc.fail(reply.cause());
                }
              });
    }
  };

  public final String path;

  IastHandler(final String path) {
    this.path = path;
  }

  public void init(final Vertx vertx) {}

  public static Optional<Handler<RoutingContext>> handlerFor(final String path) {
    return Arrays.stream(IastHandler.values())
        .filter(handler -> path.startsWith(handler.path))
        .map(handler -> (Handler<RoutingContext>) handler)
        .findFirst();
  }
}
