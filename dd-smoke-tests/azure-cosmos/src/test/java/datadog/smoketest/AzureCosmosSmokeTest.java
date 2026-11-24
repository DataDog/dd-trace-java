package datadog.smoketest;

import static datadog.smoketest.backend.AgentBackend.testAgent;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.CosmosDBEmulatorContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the Cosmos DB scenario app with the agent against the Cosmos DB emulator, in gateway mode,
 * and checks the request spans it reports, with every other instrumentation loaded.
 */
class AzureCosmosSmokeTest {
  private static final int EMULATOR_PORT = 8081;
  private static final int HEALTH_PORT = 8080;
  private static final String COSMOS_SPAN = "cosmosdb.query";
  private static final String HTTP_CLIENT_SPAN = "netty.client.request";
  private static final String CONTAINER = "container";

  private static CosmosDBEmulatorContainer emulator;
  private static LocalForwarder forwarder;
  private static Path trustStore;

  @RegisterExtension
  static final SmokeCliApp app =
      SmokeCliApp.named("azure-cosmos")
          .jar(System.getProperty("datadog.smoketest.cosmos.shadowJar.path"))
          // the emulator starts when the app launches, see emulatorEndpoint()
          .placeholder("cosmos.endpoint", AzureCosmosSmokeTest::emulatorEndpoint)
          .placeholder("cosmos.key", () -> startedEmulator().getEmulatorKey())
          .placeholder("cosmos.truststore", () -> startedTrustStore().toString())
          .jvmArgs(
              "-Dcosmos.endpoint=${cosmos.endpoint}",
              "-Dcosmos.key=${cosmos.key}",
              "-Dcosmos.endpoint-discovery=false",
              "-Djavax.net.ssl.trustStore=${cosmos.truststore}",
              "-Djavax.net.ssl.trustStorePassword=${cosmos.key}",
              "-Djavax.net.ssl.trustStoreType=PKCS12")
          .backend(testAgent())
          // the app may exit before sending telemetry
          .skipTelemetryCheck()
          .build();

  @AfterAll
  static void stopEmulator() throws IOException {
    if (forwarder != null) {
      forwarder.close();
    }
    if (emulator != null) {
      emulator.stop();
    }
  }

  @Test
  void tracesCosmosRequests() {
    app.assertCompletesWithValue(5, MINUTES, 0);

    for (String api : new String[] {"sync", "async"}) {
      DecodedTrace trace = waitForScenarioTrace(api);
      DecodedSpan root = scenarioRoot(trace, api);
      List<DecodedSpan> cosmosSpans =
          trace.getSpans().stream()
              .filter(span -> COSMOS_SPAN.equals(span.getName()))
              .sorted(Comparator.comparingLong(DecodedSpan::getStart))
              .collect(toList());

      String coll = "/dbs/smoke-" + api + "-\\d+/colls/" + CONTAINER;
      List<DecodedSpan> scenario =
          findInOrder(
              cosmosSpans,
              "Create /dbs",
              "Create /dbs/smoke-" + api + "-\\d+/colls",
              "Create " + coll + "/docs",
              "Read " + coll + "/docs/\\?",
              "Upsert " + coll + "/docs",
              "(Sql)?Query " + coll + "/docs",
              "Create " + coll + "/docs",
              "Read " + coll + "/docs/\\?",
              "Delete " + coll + "/docs/\\?",
              "Delete /dbs/smoke-" + api + "-\\d+");
      String[] statusCodes = {"201", "201", "201", "200", "200", "200", "409", "404", "204", "204"};

      for (int i = 0; i < scenario.size(); i++) {
        DecodedSpan span = scenario.get(i);
        String statusCode = statusCodes[i];
        // the 409 conflict is an error, the 404 is not
        boolean error = i == 6;
        assertAll(
            api + " " + span.getResource(),
            () -> assertEquals(root.getSpanId(), span.getParentId(), "parent"),
            () -> assertEquals("cosmosdb", span.getType()),
            () -> assertEquals("azure-cosmos", span.getMeta().get("component")),
            () -> assertEquals("client", span.getMeta().get("span.kind")),
            () -> assertEquals("cosmosdb", span.getMeta().get("db.type")),
            () -> assertEquals("gateway", span.getMeta().get("cosmosdb.connection.mode")),
            () -> assertEquals("localhost", span.getMeta().get("peer.hostname")),
            () -> assertEquals(statusCode, span.getMeta().get("db.response.status_code")),
            () -> assertEquals(error ? 1 : 0, span.getError(), "error"));
      }
      assertTrue(
          scenario.get(2).getMeta().get("db.instance").startsWith("smoke-" + api + "-"),
          "db.instance");
      assertEquals(CONTAINER, scenario.get(2).getMeta().get("cosmosdb.container"));

      Set<Long> cosmosSpanIds = cosmosSpans.stream().map(DecodedSpan::getSpanId).collect(toSet());
      for (DecodedSpan span : trace.getSpans()) {
        if (span == root) {
          continue;
        }
        if (COSMOS_SPAN.equals(span.getName())) {
          // the SDK's own account discovery requests are not traced
          assertTrue(
              span.getResource().contains(" /dbs"),
              "unexpected Cosmos request " + span.getResource());
        } else {
          // the HTTP requests of each Cosmos request are traced under its Cosmos span
          assertTrue(
              HTTP_CLIENT_SPAN.equals(span.getName()) && cosmosSpanIds.contains(span.getParentId()),
              "unexpected span " + span.getName() + " " + span.getResource());
        }
      }
    }
  }

  private static DecodedTrace waitForScenarioTrace(String api) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      for (DecodedTrace trace : app.traces().getTraces()) {
        for (DecodedSpan span : trace.getSpans()) {
          if (isScenarioRoot(span, api)) {
            return trace;
          }
        }
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return fail("no trace for the " + api + " scenario");
  }

  private static DecodedSpan scenarioRoot(DecodedTrace trace, String api) {
    return trace.getSpans().stream()
        .filter(span -> isScenarioRoot(span, api))
        .findFirst()
        .orElseThrow(IllegalStateException::new);
  }

  private static boolean isScenarioRoot(DecodedSpan span, String api) {
    return "cosmos.scenario".equals(span.getName()) && api.equals(span.getResource());
  }

  /**
   * Returns the first span matching each resource pattern, each one after the previous match. The
   * SDK sends its own metadata requests between the scenario requests.
   */
  private static List<DecodedSpan> findInOrder(List<DecodedSpan> spans, String... resources) {
    List<DecodedSpan> found = new ArrayList<>();
    int next = 0;
    for (String resource : resources) {
      Pattern expected = Pattern.compile(resource);
      DecodedSpan match = null;
      while (next < spans.size() && match == null) {
        DecodedSpan candidate = spans.get(next++);
        if (expected.matcher(stripTrailingSlash(candidate.getResource())).matches()) {
          match = candidate;
        }
      }
      if (match == null) {
        fail(
            "no span with resource "
                + resource
                + " in order, spans were: "
                + spans.stream().map(DecodedSpan::getResource).collect(toList()));
      }
      found.add(match);
    }
    return found;
  }

  private static String stripTrailingSlash(String resource) {
    return resource.endsWith("/") ? resource.substring(0, resource.length() - 1) : resource;
  }

  /**
   * The endpoint the app connects to. Recent SDK versions check the host name against the emulator
   * certificate, which names localhost but not the Docker host CI reaches the emulator through.
   */
  private static synchronized String emulatorEndpoint() {
    startedTrustStore();
    if (forwarder == null) {
      try {
        forwarder = new LocalForwarder(emulator.getHost(), emulator.getMappedPort(EMULATOR_PORT));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return "https://localhost:" + forwarder.port() + "/";
  }

  private static synchronized Path startedTrustStore() {
    if (trustStore == null) {
      CosmosDBEmulatorContainer started = startedEmulator();
      try {
        // the emulator serves a self-signed certificate
        trustStore = Files.createTempFile("azure-cosmos-emulator", ".keystore");
        try (OutputStream out = Files.newOutputStream(trustStore)) {
          started.buildNewKeyStore().store(out, started.getEmulatorKey().toCharArray());
        }
      } catch (Exception e) {
        throw new IllegalStateException("cannot build the emulator trust store", e);
      }
    }
    return trustStore;
  }

  private static synchronized CosmosDBEmulatorContainer startedEmulator() {
    if (emulator == null) {
      DockerImageName image =
          DockerImageName.parse(System.getProperty("test.cosmosdb.image"))
              .asCompatibleSubstituteFor("mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator");
      // the Java SDK needs HTTPS, which the emulator doesn't serve by default
      CosmosDBEmulatorContainer container =
          new CosmosDBEmulatorContainer(image)
              .withCommand("--protocol", "https")
              .withExposedPorts(EMULATOR_PORT, HEALTH_PORT)
              .waitingFor(Wait.forHttp("/ready").forPort(HEALTH_PORT))
              .withStartupTimeout(Duration.ofMinutes(5));
      container.start();
      emulator = container;
    }
    return emulator;
  }

  /** Relays connections accepted on localhost to the emulator. */
  private static final class LocalForwarder implements AutoCloseable {
    private final ServerSocket server;

    LocalForwarder(String host, int port) throws IOException {
      server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
      Thread acceptor =
          new Thread(
              () -> {
                while (!server.isClosed()) {
                  try {
                    Socket client = server.accept();
                    Socket target = new Socket(host, port);
                    pipe(client, target);
                    pipe(target, client);
                  } catch (IOException e) {
                    // the forwarder is closed
                  }
                }
              },
              "cosmos-emulator-forwarder");
      acceptor.setDaemon(true);
      acceptor.start();
    }

    int port() {
      return server.getLocalPort();
    }

    private static void pipe(Socket from, Socket to) {
      Thread pipe =
          new Thread(
              () -> {
                byte[] buffer = new byte[8192];
                try (InputStream in = from.getInputStream();
                    OutputStream out = to.getOutputStream()) {
                  int read;
                  while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                  }
                } catch (IOException e) {
                  // one side closed the connection
                } finally {
                  closeQuietly(from);
                  closeQuietly(to);
                }
              },
              "cosmos-emulator-pipe");
      pipe.setDaemon(true);
      pipe.start();
    }

    private static void closeQuietly(Socket socket) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // already closed
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
    }
  }
}
