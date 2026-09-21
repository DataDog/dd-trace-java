package datadog.trace.api.openfeature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.io.File;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

class StandalonePublishedJarTest {

  private static final String APP_SOURCE =
      "import static java.util.concurrent.TimeUnit.SECONDS;\n"
          + "import datadog.trace.api.openfeature.Provider;\n"
          + "import dev.openfeature.sdk.OpenFeatureAPI;\n"
          + "import dev.openfeature.sdk.MutableContext;\n"
          + "public class StandaloneOpenFeatureApp {\n"
          + "  public static void main(String[] args) {\n"
          + "    OpenFeatureAPI api = OpenFeatureAPI.getInstance();\n"
          + "    String expected = args[0];\n"
          + "    try { api.setProviderAndWait(new Provider(new Provider.Options().initTimeout(2, SECONDS))); }\n"
          + "    catch (RuntimeException failure) { if (!\"default\".equals(expected)) throw failure; }\n"
          + "    String value = api.getClient().getStringValue(\"standalone_flag\", \"default\", new MutableContext(\"user-1\"));\n"
          + "    if (!expected.equals(value)) throw new AssertionError(value);\n"
          + "    System.out.println(\"RESULT=\" + value);\n"
          + "    api.shutdown();\n"
          + "  }\n"
          + "}\n";

  private static final String CONFIGURATION =
      "{\"createdAt\":\"2026-08-20T00:00:00Z\",\"environment\":{\"name\":\"Test\"},"
          + "\"flags\":{\"standalone_flag\":{\"key\":\"standalone_flag\",\"enabled\":true,"
          + "\"variationType\":\"STRING\",\"variations\":{\"treatment\":{\"key\":\"treatment\","
          + "\"value\":\"treatment\"}},\"allocations\":[{\"key\":\"allocation\",\"splits\":[{"
          + "\"variationKey\":\"treatment\",\"shards\":[]}],\"doLog\":false}]}}}";

  private static final String UFC_RESPONSE =
      "{\"data\":{\"id\":\"1\",\"type\":\"universal-flag-configuration\",\"attributes\":"
          + CONFIGURATION
          + "}}";

  @TempDir Path temporaryDirectory;

  @Test
  void publishedJarContainsCoreAndRcButNoTracingImplementationOrTelemetrySdk() throws Exception {
    try (JarFile jar = new JarFile(System.getProperty("datadog.test.dd-openfeature.jar"))) {
      assertNotNull(
          jar.getEntry("datadog/openfeature/internal/featureflag/core/FlagEvaluator.class"));
      assertNotNull(jar.getEntry("datadog/trace/api/featureflag/ufc/v1/ServerConfiguration.class"));
      assertNotNull(
          jar.getEntry(
              "datadog/openfeature/internal/datadog/remoteconfig/DefaultConfigurationPoller.class"));
      assertNotNull(
          jar.getEntry("datadog/openfeature/internal/featureflag/RemoteConfigServiceImpl.class"));
      assertTrue(
          jar.stream()
              .noneMatch(
                  entry -> {
                    String name = entry.getName();
                    return name.contains("/datadog/trace/core/")
                        || name.startsWith("io/opentelemetry/")
                        || name.startsWith("dev/openfeature/");
                  }),
          "Standalone must exclude tracing implementation and application-owned APIs");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"agentless", "remote_config", "remote_config_unavailable"})
  void publishedJarPollsAndEvaluatesWithoutJavaAgent(String sourceMode) throws Exception {
    final boolean unavailable = "remote_config_unavailable".equals(sourceMode);
    final boolean remoteConfig = sourceMode.startsWith("remote_config");
    final String expectedValue = unavailable ? "default" : "treatment";
    final AtomicInteger configRequests = new AtomicInteger();
    final AtomicInteger rcRequests = new AtomicInteger();
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          configRequests.incrementAndGet();
          final byte[] response = UFC_RESPONSE.getBytes(UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/vnd.api+json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.createContext(
        "/info",
        exchange -> {
          final byte[] response =
              "{\"endpoints\":[\"/v0.7/config\",\"/evp_proxy/v2/\"]}".getBytes(UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    final byte[] rcResponse = remoteConfigResponse().getBytes(UTF_8);
    server.createContext(
        "/v0.7/config",
        exchange -> {
          rcRequests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(unavailable ? 503 : 200, rcResponse.length);
          exchange.getResponseBody().write(rcResponse);
          exchange.close();
        });
    server.start();

    try {
      final Path source = temporaryDirectory.resolve("StandaloneOpenFeatureApp.java");
      Files.write(source, APP_SOURCE.getBytes(UTF_8));
      final String ddOpenFeatureJar = System.getProperty("datadog.test.dd-openfeature.jar");
      assertNotNull(ddOpenFeatureJar);
      final String dependencyClasspath =
          String.join(
              File.pathSeparator,
              ddOpenFeatureJar,
              classLocation(OpenFeatureAPI.class),
              classLocation(Logger.class));
      final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      assertNotNull(compiler);
      assertEquals(
          0,
          compiler.run(
              null,
              null,
              null,
              "-classpath",
              dependencyClasspath,
              "-d",
              temporaryDirectory.toString(),
              source.toString()));

      final String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/config";
      final Process process =
          new ProcessBuilder(
                  javaBinary(),
                  "-classpath",
                  temporaryDirectory + File.pathSeparator + dependencyClasspath,
                  "-Ddd.feature.flags.configuration.source="
                      + (remoteConfig ? "remote_config" : "agentless"),
                  "-Ddd.feature.flags.configuration.source.agentless.base.url=" + endpoint,
                  "-Ddd.trace.agent.url=http://127.0.0.1:" + server.getAddress().getPort(),
                  "-Ddd.remote_configuration.enabled=true",
                  "-Ddd.remote_config.poll_interval.seconds=1",
                  "-Ddd.feature.flags.configuration.source.agentless.poll.interval.seconds=60",
                  "-Ddd.feature.flags.configuration.source.agentless.request.timeout.seconds=5",
                  "-Ddd.flagging.evaluation.counts.enabled=false",
                  "StandaloneOpenFeatureApp",
                  expectedValue)
              .redirectErrorStream(true)
              .start();

      try {
        assertTrue(process.waitFor(15, SECONDS), "standalone application did not exit");
      } finally {
        if (process.isAlive()) {
          process.destroyForcibly();
        }
      }
      final String output = new String(process.getInputStream().readAllBytes(), UTF_8);
      assertEquals(
          0,
          process.exitValue(),
          output + System.lineSeparator() + "configuration requests=" + configRequests.get());
      assertTrue(output.contains("RESULT=" + expectedValue), output);
      if (remoteConfig) {
        assertTrue(rcRequests.get() > 0, "standalone runtime did not poll Remote Configuration");
        assertEquals(0, configRequests.get(), "explicit RC must not fall back to CDN polling");
      } else {
        assertTrue(configRequests.get() > 0, "standalone runtime did not poll CDN configuration");
        assertEquals(0, rcRequests.get(), "direct mode must not poll Remote Configuration");
      }
    } finally {
      server.stop(0);
    }
  }

  private static String remoteConfigResponse() throws Exception {
    final byte[] configuration = CONFIGURATION.getBytes(UTF_8);
    final String hash =
        String.format(
            "%064x", new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(configuration)));
    final String path = "datadog/2/FFE_FLAGS/standalone/config";
    final String targets =
        "{\"signed\":{\"_type\":\"targets\",\"version\":1,\"targets\":{\""
            + path
            + "\":{\"custom\":{\"v\":1},\"length\":"
            + configuration.length
            + ",\"hashes\":{\"sha256\":\""
            + hash
            + "\"}}}}}";
    return "{\"client_configs\":[\""
        + path
        + "\"],\"roots\":[],\"targets\":\""
        + Base64.getEncoder().encodeToString(targets.getBytes(UTF_8))
        + "\",\"target_files\":[{\"path\":\""
        + path
        + "\",\"raw\":\""
        + Base64.getEncoder().encodeToString(configuration)
        + "\"}]}";
  }

  private static String classLocation(final Class<?> type) throws Exception {
    return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  }

  private static String javaBinary() {
    return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
  }
}
