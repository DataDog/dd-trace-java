package datadog.test.agent;

import static java.util.Collections.emptyList;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

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

  public List<AgentTrace> traces() {
    Request request = new Request.Builder().url(this.baseUrl + "test/traces").build();
    try (Response response = this.client.newCall(request).execute()) {
      ResponseBody body = response.body();
      if (body == null) {
        return emptyList();
      } else {
        return AgentTrace.fromJsonArray(body.string());
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public List<AgentTrace> waitForTraces(int traceCount) throws TimeoutException {
    List<AgentTrace> traces;
    // Retry every 500ms during 20s max
    int retry = 0;
    int maxRetries = 40;
    int retryDelay = 500;
    do {
      traces = traces();
      if (traces.size() >= traceCount) {
        return traces;
      }
      retry++;
      try {
        Thread.sleep(retryDelay);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    } while (retry < maxRetries);
    throw new TimeoutException("Failed to retrieve " + traceCount + " traces from trace agent. Only get " + traces.size() + " trace(s).")
    ;
  }
}
