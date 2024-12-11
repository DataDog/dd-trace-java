package datadog.test.agent;

import static java.util.Collections.emptyList;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import java.io.IOException;
import java.util.List;

public class TestAgentClient {

  private final OkHttpClient client;
  private final String baseUrl;
  private final int port;

  public TestAgentClient(String host, int port) {
    this.baseUrl = "http://" + host + ":" + port + "/";
    this.port = port;

    this.client = new OkHttpClient.Builder()
        .build();
  }

  public int port() {
    return this.port;
  }

  public List<Trace> traces() {
    Request request = new Request.Builder().url(this.baseUrl + "test/traces").build();
    try (Response response = this.client.newCall(request).execute()) {
      ResponseBody body = response.body();
      if (body == null) {
        return emptyList();
      } else {
        return Trace.fromJsonArray(body.string());
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
