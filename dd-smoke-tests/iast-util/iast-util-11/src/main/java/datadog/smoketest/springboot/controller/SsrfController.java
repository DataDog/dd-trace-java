package datadog.smoketest.springboot.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ssrf")
public class SsrfController {

  @PostMapping("/java-net")
  public String javaNet(
      @RequestParam(value = "url", required = false) final String url,
      @RequestParam(value = "async", required = false) final boolean async,
      @RequestParam(value = "promise", required = false) final boolean promise) {
    HttpClient httpClient = HttpClient.newBuilder().build();
    try {
      String uri = url.startsWith("http") ? url : "http://" + url;
      if (async) {
        HttpRequest httpRequest = HttpRequest.newBuilder().uri(new URI(uri)).build();
        if (promise) {
          httpClient.sendAsync(
              httpRequest,
              HttpResponse.BodyHandlers.ofString(),
              (initiatingRequest, pushPromiseRequest, acceptor) -> {});
        } else {
          httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString());
        }
      } else {
        HttpRequest httpRequest =
            HttpRequest.newBuilder()
                .uri(new URI(uri))
                .timeout(
                    java.time.Duration.ofSeconds(
                        1)) // prevents Idle timeout expired in jetty servers when the client is not
                // responding in sync mode
                .build();
        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      }
    } catch (Exception e) {
      // Do nothing
    }
    return "ok";
  }
}
