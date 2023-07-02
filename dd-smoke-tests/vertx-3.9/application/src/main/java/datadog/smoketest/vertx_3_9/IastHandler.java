package datadog.smoketest.vertx_3_9;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.Arrays;
import java.util.Optional;
import java.util.Vector;

/** The datadog.smoketest package is needed so the class is instrumented */
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
  INSECURE_COOKIE("/insecurecookie") {
    @Override
    public void handle(final RoutingContext rc) {
      rc.response().addCookie(Cookie.cookie("userA-id", "7")).end("Received ");
    }
  },
  INSECURE_COOKIE_HEADER("/insecurecookieheader") {
    @Override
    public void handle(final RoutingContext rc) {
      rc.response().putHeader("Set-Cookie", "user-id=7").end("Received ");
    }
  },
  INSECURE_COOKIE_HEADER2("/insecurecookieheader2") {
    @Override
    public void handle(final RoutingContext rc) {
      Vector<String> values = new Vector<>();
      values.add("firstcookie=b");
      values.add("user-id=7");
      rc.response().putHeader("Set-cookie", values).end("Received ");
    }
  },
  UNVALIDATED_REDIRECT_REROUTE1("/unvaidatedredirectreroute1") {
    @Override
    public void handle(final RoutingContext rc) {
      final String path = rc.request().getParam("path");
      rc.reroute(path);
    }
  },
  UNVALIDATED_REDIRECT_REROUTE2("/unvaidatedredirectreroute2") {
    @Override
    public void handle(final RoutingContext rc) {
      final String path = rc.request().getParam("path");
      rc.reroute(HttpMethod.GET, path);
    }
  },
  UNVALIDATED_REDIRECT_HEADER("/unvaidatedredirectheader") {
    @Override
    public void handle(final RoutingContext rc) {
      final String name = rc.request().getParam("name");
      final String value = rc.request().getParam("value");
      rc.response().putHeader(name, value).end("Redirected ");
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
          .request(
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
