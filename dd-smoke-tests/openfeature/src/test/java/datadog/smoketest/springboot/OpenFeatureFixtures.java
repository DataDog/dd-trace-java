package datadog.smoketest.springboot;

import static datadog.trace.agent.test.server.http.JavaTestHttpServer.httpServer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

import datadog.smoketest.SmokeServerApp;
import datadog.smoketest.backend.AgentBackend;
import datadog.smoketest.backend.EvpProxy;
import datadog.smoketest.backend.MockAgentBackend;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.agent.test.utils.OkHttpUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The application, flag configuration and evaluation cases shared by the OpenFeature smoke tests.
 */
final class OpenFeatureFixtures {
  static final String APPLICATION_JAR =
      System.getProperty("datadog.smoketest.springboot.shadowJar.path");
  static final String AGENTLESS_BASE_URL =
      "DD_FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL";
  static final String FLAG_CONFIGURATION_PATH = "datadog/2/FFE_FLAGS/1/config";

  /** The flag configuration, from the system tests data. */
  static final String UFC_CONFIG = read("ufc-config.json");

  /** The flag configuration, as the Datadog managed CDN serves it. */
  private static final byte[] CDN_RESPONSE =
      ("{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":" + UFC_CONFIG + "}}")
          .getBytes(UTF_8);

  /** Serves the flag configuration as the Datadog managed CDN does. */
  static final JavaTestHttpServer CDN =
      httpServer(
          server ->
              server.handlers(
                  handlers ->
                      handlers.prefix(
                          "/api/v2/feature-flagging/config/rules-based/server",
                          api ->
                              api.getResponse()
                                  .status(200)
                                  .sendWithType("application/json", CDN_RESPONSE))));

  private static final MediaType JSON = MediaType.parse("application/json");
  private static final OkHttpClient CLIENT = OkHttpUtils.client();

  private OpenFeatureFixtures() {}

  /**
   * Creates the agent stand-in. The dd-apm-test-agent neither proxies the flag evaluation events
   * nor advertises the event platform proxy headers, so the tests use the in-process mock agent.
   *
   * @return A new mock agent backend.
   */
  @SuppressWarnings("deprecation")
  static MockAgentBackend agent() {
    return AgentBackend.mockAgent();
  }

  /**
   * Starts building the application with the agentless configuration source, polling the CDN.
   *
   * @param name The application name.
   * @return The application builder.
   */
  static SmokeServerApp.Builder agentlessApp(String name) {
    return SmokeServerApp.named(name)
        .jar(APPLICATION_JAR)
        .args("--server.port=${app.httpPort}")
        .env(AGENTLESS_BASE_URL, CDN.getAddress().toString())
        .backend(agent());
  }

  /**
   * Gets the requests the application sent through the agent event platform proxy.
   *
   * @param app The application, reporting to the {@link #agent()}.
   * @return The event platform proxy requests.
   */
  static EvpProxy evpProxy(SmokeServerApp app) {
    return ((MockAgentBackend) app.backend()).evpProxy();
  }

  /**
   * Lists the system tests evaluation cases.
   *
   * @return The evaluation cases, sorted by file name then position.
   */
  static List<EvaluationCase> evaluationCases() {
    try (Stream<Path> listing = Files.list(resourcePath("evaluation-cases"))) {
      List<Path> files =
          listing
              .filter(path -> path.toString().endsWith(".json"))
              .sorted(comparing(path -> path.getFileName().toString()))
              .collect(toList());
      List<EvaluationCase> cases = new ArrayList<>();
      for (Path file : files) {
        cases.addAll(parseCases(file.getFileName().toString(), readFile(file)));
      }
      if (cases.isEmpty()) {
        throw new IllegalStateException("No evaluation case found");
      }
      return cases;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Gets the first system tests evaluation case of a file.
   *
   * @param fileName The evaluation cases file name.
   * @return The first evaluation case of the file.
   */
  static EvaluationCase evaluationCase(String fileName) {
    return parseCases(fileName, read("evaluation-cases/" + fileName)).get(0);
  }

  /**
   * Evaluates a flag through the application.
   *
   * @param app The application.
   * @param request The evaluation request.
   * @return The evaluation response.
   * @throws IOException If the request failed.
   */
  static Evaluation evaluate(SmokeServerApp app, Map<String, Object> request) throws IOException {
    Request httpRequest =
        new Request.Builder()
            .url(app.url() + "/openfeature/evaluate")
            .post(RequestBody.create(JSON, Json.write(request)))
            .build();
    try (Response response = CLIENT.newCall(httpRequest).execute()) {
      return new Evaluation(
          response.code(), response.body() == null ? "" : response.body().string());
    }
  }

  private static List<EvaluationCase> parseCases(String fileName, String json) {
    List<Object> entries = Json.array(Json.parse(json));
    List<EvaluationCase> cases = new ArrayList<>();
    for (int index = 0; index < entries.size(); index++) {
      cases.add(new EvaluationCase(fileName, index, Json.object(entries.get(index))));
    }
    return cases;
  }

  private static Path resourcePath(String name) {
    URL url =
        Thread.currentThread().getContextClassLoader().getResource("ffe-system-test-data/" + name);
    if (url == null) {
      throw new IllegalStateException("Missing system tests data: " + name);
    }
    try {
      return Paths.get(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String read(String name) {
    try {
      return readFile(resourcePath(name));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String readFile(Path file) throws IOException {
    return new String(Files.readAllBytes(file), UTF_8);
  }

  /** A system tests evaluation case: the evaluation request, along with its expected result. */
  static final class EvaluationCase {
    private final String fileName;
    private final int index;
    final Map<String, Object> request;

    EvaluationCase(String fileName, int index, Map<String, Object> request) {
      this.fileName = fileName;
      this.index = index;
      this.request = request;
    }

    String flag() {
      return (String) this.request.get("flag");
    }

    String targetingKey() {
      return (String) this.request.get("targetingKey");
    }

    Map<String, Object> result() {
      return Json.object(this.request.get("result"));
    }

    @Override
    public String toString() {
      return this.fileName + "[" + this.index + "] flag=" + flag();
    }
  }

  /** The application response to an evaluation request. */
  static final class Evaluation {
    final int code;
    final String body;

    Evaluation(int code, String body) {
      this.code = code;
      this.body = body;
    }

    Map<String, Object> json() {
      return Json.object(Json.parse(this.body));
    }
  }
}
