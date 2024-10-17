package datadog.smoketest.springboot.controller;

import java.net.HttpURLConnection;
import java.net.URL;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
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
      @RequestParam(value = "host", required = false) final String host) {
    try {
      final URL target = url != null ? new URL(url) : new URL("https", host, 443, "/test");
      final HttpURLConnection conn = (HttpURLConnection) target.openConnection();
      conn.disconnect();
    } catch (final Exception e) {
    }
    return "ok";
  }

  @PostMapping("/commons-httpclient2")
  public String commonsHttpClient2(@RequestParam(value = "url") final String url) {
    final HttpClient client = new HttpClient();
    try {
      final HttpMethod method = new GetMethod(url);
      client.executeMethod(method);
      method.releaseConnection();
    } catch (final Exception e) {
    }
    return "ok";
  }

  // AKKA HTTP

  // APACHE HTTP CLIENT 4 / 5

  // GRIZZLY CLIENT / HTTP

  // JAVA HTTP CLIENT

  // JAVA NET

  // JETTY CLIENT

  // NETTY CLIENT

  // OK HTTP CLIENT

}
