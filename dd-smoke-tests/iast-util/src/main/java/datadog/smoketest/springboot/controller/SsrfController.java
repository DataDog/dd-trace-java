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
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
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
      @RequestParam(value = "urlHandler", required = false) final String urlHandler,
      @RequestParam(value = "host", required = false) final String host) {
    CloseableHttpClient client = HttpClients.createDefault();
    try {
      if (host != null) {
        final org.apache.hc.core5.http.HttpHost httpHost =
            new org.apache.hc.core5.http.HttpHost(host);
        final org.apache.hc.client5.http.classic.methods.HttpGet request =
            new org.apache.hc.client5.http.classic.methods.HttpGet("/");
        client.execute(httpHost, request);
      } else if (url != null) {
        final org.apache.hc.client5.http.classic.methods.HttpGet request =
            new org.apache.hc.client5.http.classic.methods.HttpGet(url);
        client.execute(request);
      } else if (urlHandler != null) {
        final org.apache.hc.client5.http.classic.methods.HttpGet request =
            new org.apache.hc.client5.http.classic.methods.HttpGet(urlHandler);
        client.execute(request, response -> null);
      }
      client.close();
    } catch (Exception e) {
    }
    return "ok";
  }

  @PostMapping("/apache-httpasyncclient")
  public String apacheHttpAsyncClient(
      @RequestParam(value = "url", required = false) final String url,
      @RequestParam(value = "host", required = false) final String host,
      @RequestParam(value = "urlProducer", required = false) final String urlProducer) {
    final CloseableHttpAsyncClient client = HttpAsyncClients.createDefault();
    client.start();
    try {
      if (host != null) {
        final HttpHost httpHost = new HttpHost(host);
        client.execute(httpHost, new HttpGet("/"), null);
      } else if (url != null) {
        final HttpGet request = new HttpGet(url);
        client.execute(request, null);
      } else if (urlProducer != null) {
        final HttpAsyncRequestProducer producer = HttpAsyncMethods.create(new HttpGet(urlProducer));
        client.execute(producer, null, null);
      }
    } catch (Exception e) {
    } finally {
      try {
        client.close();
      } catch (Exception e) {
      }
    }
    return "ok";
  }
}
