package datadog.smoketest.appsec.springboot.controller;

import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;
import static org.springframework.web.bind.annotation.RequestMethod.PUT;

import com.fasterxml.jackson.databind.JsonNode;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import datadog.appsec.api.blocking.BlockingException;
import datadog.smoketest.appsec.springboot.service.AsyncService;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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

  @PostMapping("/api_security/request-body-string")
  public String requestBodyString(@RequestBody String body) {
    return body;
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
    } catch (final BlockingException e) {
      throw e;
    } catch (final Throwable e) {
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
    } catch (final BlockingException e) {
      throw e;
    } catch (final Exception e) {
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
    } catch (final BlockingException e) {
      throw e;
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
    } catch (final BlockingException e) {
      throw e;
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
    } catch (final BlockingException e) {
      throw e;
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

  @PostMapping("/cmdi/arrayCmd")
  public String shiArrayCmd(@RequestParam("cmd") String[] arrayCmd) {
    withProcess(() -> Runtime.getRuntime().exec(arrayCmd));
    return "EXECUTED";
  }

  @PostMapping("/cmdi/arrayCmdWithParams")
  public String shiArrayCmdWithParams(
      @RequestParam("cmd") String[] arrayCmd, @RequestParam("params") String[] params) {
    withProcess(() -> Runtime.getRuntime().exec(arrayCmd, params));
    return "EXECUTED";
  }

  @PostMapping("/cmdi/arrayCmdWithParamsAndFile")
  public String shiArrayCmdWithParamsAndFile(
      @RequestParam("cmd") String[] arrayCmd, @RequestParam("params") String[] params) {
    withProcess(() -> Runtime.getRuntime().exec(arrayCmd, params, new File("")));
    return "EXECUTED";
  }

  @PostMapping("/cmdi/processBuilder")
  public String shiProcessBuilder(@RequestParam("cmd") String[] cmd) {
    withProcess(() -> new ProcessBuilder(cmd).start());
    return "EXECUTED";
  }

  @PostMapping("/shi/cmd")
  public String shiCmd(@RequestParam("cmd") String cmd) {
    withProcess(() -> Runtime.getRuntime().exec(cmd));
    return "EXECUTED";
  }

  @PostMapping("/shi/cmdWithParams")
  public String shiCmdWithParams(
      @RequestParam("cmd") String cmd, @RequestParam("params") String[] params) {
    withProcess(() -> Runtime.getRuntime().exec(cmd, params));
    return "EXECUTED";
  }

  @PostMapping("/shi/cmdParamsAndFile")
  public String shiCmdParamsAndFile(
      @RequestParam("cmd") String cmd, @RequestParam("params") String[] params) {
    withProcess(() -> Runtime.getRuntime().exec(cmd, params, new File("")));
    return "EXECUTED";
  }

  @GetMapping("/api_security/sampling/{status_code}")
  public ResponseEntity<String> apiSecuritySampling(@PathVariable("status_code") int statusCode) {
    return ResponseEntity.status(statusCode).body("EXECUTED");
  }

  @PostMapping(value = "/api_security/jackson", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<JsonNode> apiSecurityJackson(@RequestBody final JsonNode body) {
    return ResponseEntity.status(200).body(body);
  }

  @GetMapping("/custom-headers")
  public ResponseEntity<String> customHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.add("X-Test-Header-1", "value1");
    headers.add("X-Test-Header-2", "value2");
    headers.add("X-Test-Header-3", "value3");
    headers.add("X-Test-Header-4", "value4");
    headers.add("X-Test-Header-5", "value5");
    headers.add("WWW-Authenticate", "value6");
    headers.add("Proxy-Authenticate", "value7");
    headers.add("Set-Cookie", "value8");
    headers.add("Authentication-Info", "value9");
    headers.add("Proxy-Authentication-Info", "value10");
    return new ResponseEntity<>("Custom headers added", headers, HttpStatus.OK);
  }

  @GetMapping("/exceedResponseHeaders")
  public ResponseEntity<String> exceedResponseHeaders() {
    HttpHeaders headers = new HttpHeaders();
    for (int i = 1; i <= 50; i++) {
      headers.add("X-Test-Header-" + i, "value" + i);
    }
    headers.add("content-language", "en-US");
    return new ResponseEntity<>("Custom headers added", headers, HttpStatus.OK);
  }

  @PostMapping("/waf-event-with-body")
  public String wafEventWithBody(@RequestBody String body) {
    return "EXECUTED";
  }

  @PostMapping("/api_security/response")
  public ResponseEntity<Map<String, Object>> apiSecurityResponse(
      @RequestBody Map<String, Object> body) {
    // This endpoint is used to test API security response handling
    // It simply returns the body received in the request
    return ResponseEntity.ok(body);
  }

  @RequestMapping(
      value = "/api_security/http_client/okHttp2",
      method = {POST, GET, PUT})
  public ResponseEntity<String> apiSecurityHttpClientOkHttp2(final HttpServletRequest request)
      throws IOException {
    // create an internal http request to the echo endpoint to validate the http client library
    String url = getEchoUrl(request);
    final String redirect = request.getParameter("redirect");
    if (redirect != null) {
      url += "?redirect=true";
    }
    Request.Builder clientRequest = new Request.Builder().url(url);
    if (requiresBody(request.getMethod())) {
      final String contentType = request.getContentType();
      final byte[] data = readFully(request.getInputStream());
      clientRequest =
          clientRequest.method(
              request.getMethod(),
              com.squareup.okhttp.RequestBody.create(
                  com.squareup.okhttp.MediaType.parse(contentType), data));
    } else {
      clientRequest.method(request.getMethod(), null);
    }
    final String statusCode = request.getHeader("Status");
    if (statusCode != null) {
      clientRequest = clientRequest.header("Status", statusCode);
    }
    final String witness = request.getHeader("Witness");
    if (witness != null) {
      clientRequest = clientRequest.header("Witness", witness);
    }
    final String echoHeaders = request.getHeader("echo-headers");
    if (echoHeaders != null) {
      clientRequest = clientRequest.header("echo-headers", echoHeaders);
    }
    final Response clientResponse = new OkHttpClient().newCall(clientRequest.build()).execute();
    return ResponseEntity.status(200).body(clientResponse.body().string());
  }

  @RequestMapping(
      value = "/api_security/http_client/okHttp3",
      method = {POST, GET, PUT})
  public ResponseEntity<String> apiSecurityHttpClientOkHttp3(final HttpServletRequest request)
      throws IOException {
    // create an internal http request to the echo endpoint to validate the http client library
    final okhttp3.HttpUrl.Builder url = okhttp3.HttpUrl.parse(getEchoUrl(request)).newBuilder();
    final String redirect = request.getParameter("redirect");
    if (redirect != null) {
      url.addQueryParameter("redirect", "true");
    }
    okhttp3.Request.Builder clientRequest = new okhttp3.Request.Builder().url(url.build());
    if (requiresBody(request.getMethod())) {
      final String contentType = request.getContentType();
      final byte[] data = readFully(request.getInputStream());
      clientRequest =
          clientRequest.method(
              request.getMethod(),
              okhttp3.RequestBody.create(okhttp3.MediaType.parse(contentType), data));
    } else {
      clientRequest.method(request.getMethod(), null);
    }
    final String statusCode = request.getHeader("Status");
    if (statusCode != null) {
      clientRequest = clientRequest.header("Status", statusCode);
    }
    final String witness = request.getHeader("Witness");
    if (witness != null) {
      clientRequest = clientRequest.header("Witness", witness);
    }
    final String echoHeaders = request.getHeader("echo-headers");
    if (echoHeaders != null) {
      clientRequest = clientRequest.header("echo-headers", echoHeaders);
    }
    final okhttp3.Response clientResponse =
        new okhttp3.OkHttpClient().newCall(clientRequest.build()).execute();
    return ResponseEntity.status(200).body(clientResponse.body().string());
  }

  @RequestMapping(
      value = "/echo",
      method = {POST, GET, PUT})
  public ResponseEntity<String> echo(final HttpServletRequest request)
      throws IOException, URISyntaxException {
    final String redirect = request.getParameter("redirect");
    if (redirect != null) {
      return ResponseEntity.status(HttpStatus.FOUND).location(new URI("/echo")).build();
    }
    final String statusHeader = request.getHeader("Status");
    final int statusCode = statusHeader == null ? 200 : Integer.parseInt(statusHeader);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(statusCode);
    final String echoHeaders = request.getHeader("echo-headers");
    if (echoHeaders != null) {
      response = response.header("echo-headers", echoHeaders);
    }
    if (requiresBody(request.getMethod())) {
      final String contentType = request.getContentType();
      final byte[] data = readFully(request.getInputStream());
      return response.contentType(MediaType.parseMediaType(contentType)).body(new String(data));
    } else {
      return response.body("OK");
    }
  }

  private static boolean requiresBody(final String method) {
    return method.equalsIgnoreCase("POST") || method.equalsIgnoreCase("PUT");
  }

  private static String getEchoUrl(final HttpServletRequest request) {
    return ServletUriComponentsBuilder.fromRequestUri(request)
        .replacePath("/echo")
        .build()
        .toUriString();
  }

  private static byte[] readFully(final InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] data = new byte[4096]; // 4KB buffer
    int bytesRead;
    while ((bytesRead = in.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, bytesRead);
    }
    return buffer.toByteArray();
  }

  private void withProcess(final Operation<Process> op) {
    Process process = null;
    try {
      process = op.run();
    } catch (final Throwable e) {
      // ignore it
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private interface Operation<E> {
    E run() throws Throwable;
  }
}
