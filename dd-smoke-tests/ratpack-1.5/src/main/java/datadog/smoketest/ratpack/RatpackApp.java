package datadog.smoketest.ratpack;

import static ratpack.jackson.Jackson.json;

import com.fasterxml.jackson.databind.JsonNode;
import ratpack.server.RatpackServer;

public class RatpackApp {

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getProperty("ratpack.http.port", "8080"));
    RatpackServer.start(
        server ->
            server
                .serverConfig(config -> config.port(port))
                .handlers(
                    chain ->
                        chain
                            .path(
                                "api_security/sampling/:status_code",
                                ctx -> {
                                  ctx.getResponse()
                                      .status(
                                          Integer.parseInt(ctx.getPathTokens().get("status_code")))
                                      .send("EXECUTED");
                                })
                            .path(
                                "api_security/response",
                                ctx ->
                                    ctx.parse(JsonNode.class)
                                        .then(
                                            node -> {
                                              ctx.getResponse().status(200);
                                              ctx.render(json(node));
                                            }))
                            .all(
                                ctx -> {
                                  ctx.getResponse().status(404).send("Not Found");
                                })));
  }
}
