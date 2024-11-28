package datadog.smoketest.appsec.springboot.controller;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import datadog.smoketest.appsec.springboot.service.AsyncService;
import java.io.File;
import java.net.URL;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebController {

  @Autowired private AsyncService myAsyncService;

  @RequestMapping("/greeting")
  public String greeting() {
    return "Sup AppSec Dawg";
  }

  @GetMapping("/id/{id}")
  public String pathParam(@PathVariable("id") String id) {
    return id;
  }

  static class BodyMappedClass {
    public String v;
  }

  @PostMapping("/request-body")
  public String requestBody(@RequestBody BodyMappedClass obj) {
    return obj.v;
  }

  @GetMapping("/sqli/query")
  public String sqliQuery(@RequestParam("id") String id) throws Exception {
    Connection conn = DriverManager.getConnection("jdbc:h2:mem:testdb", "sa", "");
    conn.createStatement().execute("SELECT 1 FROM DUAL WHERE '1' = '" + id + "'");
    return "EXECUTED";
  }

  @GetMapping("parallel/sqli/query")
  public String sqliQueryParallel(@RequestParam("id") String id) {
    myAsyncService.performAsyncTask(id);
    return "EXECUTED";
  }

  @GetMapping("/sqli/header")
  public String sqliHeader(@RequestHeader("x-custom-header") String id) throws Exception {
    Connection conn = DriverManager.getConnection("jdbc:h2:mem:testdb", "sa", "");
    conn.createStatement().execute("SELECT 1 FROM DUAL WHERE '1' = '" + id + "'");
    return "EXECUTED";
  }

  @GetMapping("/ssrf/query")
  public String ssrfQuery(@RequestParam("domain") final String domain) {
    try {
      new URL("http://" + domain).openStream().close();
    } catch (Throwable e) {
      // ignore errors opening connection
    }
    return "EXECUTED";
  }

  @GetMapping("/ssrf/apache-httpclient4")
  public String apacheHttpClient4(@RequestParam("domain") final String domain) {
    final DefaultHttpClient client = new DefaultHttpClient();
    try {
      final HttpGet request = new HttpGet("http://" + domain);
      client.execute(request);
    } catch (Exception e) {
      // ignore errors opening connection
    }
    client.getConnectionManager().shutdown();
    return "EXECUTED";
  }

  @GetMapping("/ssrf/commons-httpclient2")
  public String commonsHttpClient2(@RequestParam("domain") final String domain) {
    final HttpClient client = new HttpClient();
    final HttpMethod method = new GetMethod("http://" + domain);
    try {
      client.executeMethod(method);
    } catch (final Exception e) {
      // ignore errors opening connection
    }
    method.releaseConnection();
    return "EXECUTED";
  }

  @GetMapping("/ssrf/okHttp2")
  public String okHttp2(@RequestParam(value = "domain") final String domain) {
    final OkHttpClient client = new OkHttpClient();
    final Request request = new Request.Builder().url("http://" + domain).build();
    try {
      client.newCall(request).execute();
    } catch (final Exception e) {
      // ignore errors opening connection
    }
    client.getDispatcher().getExecutorService().shutdown();
    client.getConnectionPool().evictAll();
    return "EXECUTED";
  }

  @GetMapping("/ssrf/okHttp3")
  public String okHttp3(@RequestParam("domain") final String domain) {
    final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
    final okhttp3.Request request = new okhttp3.Request.Builder().url("http://" + domain).build();
    try {
      client.newCall(request).execute();
    } catch (final Exception e) {
      // ignore errors opening connection
    }
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
    return "EXECUTED";
  }

  @GetMapping("/lfi/file")
  public String lfiFile(@RequestParam("path") String path) {
    new File(path);
    return "EXECUTED";
  }

  @GetMapping("/lfi/paths")
  public String lfiPaths(@RequestParam("path") String path) {
    Paths.get(path);
    return "EXECUTED";
  }

  @GetMapping("/lfi/path")
  public String lfiPath(@RequestParam("path") String path) {
    new File(System.getProperty("user.dir")).toPath().resolve(path);
    return "EXECUTED";
  }

  @RequestMapping("/session")
  public ResponseEntity<String> session(final HttpServletRequest request) {
    final HttpSession session = request.getSession(true);
    return new ResponseEntity<>(session.getId(), HttpStatus.OK);
  }
}
