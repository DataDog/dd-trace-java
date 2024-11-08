package datadog.smoketest.springboot.controller;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import java.net.HttpURLConnection;
import java.net.URL;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ssrf")
public class SsrfController {

  @PostMapping
  public String ssrf(
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

  @PostMapping("/apache-httpclient4")
  public String apacheHttpClient4(
      @RequestParam(value = "url", required = false) final String url,
      @RequestParam(value = "host", required = false) final String host) {
    final DefaultHttpClient client = new DefaultHttpClient();
    try {
      if (host != null) {
        final HttpHost httpHost = new HttpHost(host);
        final BasicHttpRequest request = new BasicHttpRequest("GET", "/");
        client.execute(httpHost, request);
      } else if (url != null) {
        final HttpGet request = new HttpGet(url);
        client.execute(request);
      }
    } catch (Exception e) {
    }
    client.getConnectionManager().shutdown();
    return "ok";
  }

  @PostMapping("/commons-httpclient2")
  public String commonsHttpClient2(@RequestParam(value = "url") final String url) {
    final HttpClient client = new HttpClient();
    final HttpMethod method = new GetMethod(url);
    try {
      client.executeMethod(method);
    } catch (final Exception e) {
    }
    method.releaseConnection();
    return "ok";
  }

  @PostMapping("/okHttp2")
  public String okHttp2(@RequestParam(value = "url") final String url) {
    final OkHttpClient client = new OkHttpClient();
    final Request request = new Request.Builder().url(url).build();
    try {
      client.newCall(request).execute();
    } catch (final Exception e) {
    }
    client.getDispatcher().getExecutorService().shutdown();
    client.getConnectionPool().evictAll();
    return "ok";
  }

  @PostMapping("/okHttp3")
  public String okHttp3(@RequestParam(value = "url") final String url) {
    final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
    final okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
    try (final okhttp3.Response response = client.newCall(request).execute()) {
    } catch (final Exception e) {
    }
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
    return "ok";
  }

  @PostMapping("/apache-httpclient5")
  public String apacheHttpClient5(
      @RequestParam(value = "url", required = false) final String url,
      @RequestParam(value = "host", required = false) final String host) {
    CloseableHttpClient client = HttpClients.createDefault();
    org.apache.hc.client5.http.classic.methods.HttpGet request =
        new org.apache.hc.client5.http.classic.methods.HttpGet(url);
    try {
      if (host != null) {
        //        final HttpHost httpHost = new HttpHost(host);
        //        final BasicHttpRequest request = new BasicHttpRequest("GET", "/");
        //        client.execute(httpHost, request);
      } else if (url != null) {
        client.execute(request);
      }
      client.close();
    } catch (Exception e) {
    }
    return "ok";
  }
}
