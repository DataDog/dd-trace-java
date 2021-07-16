package io.sqreen.testapp.sampleapp;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@Controller
public class SignalsController {

  private String prepareOutput(String url, int status) {
    return String.format("Request => %s%nResponse status => %d", url, status);
  }

  @GetMapping(value = "/spring/http", produces = "text/plain")
  @ResponseBody
  public String springHttpClient(@RequestParam(name = "q") String targetUrl) {
    RestTemplate rest = new RestTemplate();

    HttpEntity<String> requestEntity = new HttpEntity<String>("", null);
    ResponseEntity<String> responseEntity =
        rest.exchange(targetUrl, HttpMethod.GET, requestEntity, String.class);

    return prepareOutput(targetUrl, responseEntity.getStatusCode().value());
  }

  @GetMapping(value = "/apache/http", produces = "text/plain")
  @ResponseBody
  public String apacheHttpClient(@RequestParam(name = "q") String targetUrl) throws IOException {
    CloseableHttpClient httpClient = HttpClients.createDefault();
    try {
      HttpGet request = new HttpGet(targetUrl);

      CloseableHttpResponse response = httpClient.execute(request);
      try {

        StatusLine statusLine = response.getStatusLine();
        return prepareOutput(targetUrl, statusLine.getStatusCode());

      } finally {
        response.close();
      }

    } finally {
      httpClient.close();
    }
  }

  @GetMapping(value = "/http", produces = "text/plain")
  @ResponseBody
  public String javaHttpClient(@RequestParam(name = "q") String targetUrl) throws IOException {

    URL url = new URL(targetUrl);
    HttpURLConnection con = (HttpURLConnection) url.openConnection();
    con.setRequestMethod("GET");
    con.setConnectTimeout(5000);
    con.setReadTimeout(5000);

    int status = con.getResponseCode();

    return prepareOutput(targetUrl, status);
  }
}
