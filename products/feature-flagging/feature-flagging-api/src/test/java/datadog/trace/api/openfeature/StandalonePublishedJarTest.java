package datadog.trace.api.openfeature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
          + "    api.setProviderAndWait(new Provider(new Provider.Options().initTimeout(5, SECONDS)));\n"
          + "    String value = api.getClient().getStringValue(\"standalone_flag\", \"default\", new MutableContext(\"user-1\"));\n"
          + "    if (!\"treatment\".equals(value)) throw new AssertionError(value);\n"
          + "    System.out.println(\"RESULT=\" + value);\n"
          + "    api.shutdown();\n"
          + "  }\n"
          + "}\n";

  private static final String UFC_RESPONSE =
      "{\"data\":{\"id\":\"1\",\"type\":\"universal-flag-configuration\",\"attributes\":{"
          + "\"createdAt\":\"2026-08-20T00:00:00Z\",\"environment\":{\"name\":\"Test\"},"
          + "\"flags\":{\"standalone_flag\":{\"key\":\"standalone_flag\",\"enabled\":true,"
          + "\"variationType\":\"STRING\",\"variations\":{\"treatment\":{\"key\":\"treatment\","
          + "\"value\":\"treatment\"}},\"allocations\":[{\"key\":\"allocation\",\"splits\":[{"
          + "\"variationKey\":\"treatment\",\"shards\":[]}],\"doLog\":false}]}}}}}";

  @TempDir Path temporaryDirectory;

  @Test
  void publishedJarPollsAndEvaluatesWithoutJavaAgent() throws Exception {
    final AtomicInteger configRequests = new AtomicInteger();
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
                  "-Ddd.feature.flags.configuration.source=agentless",
                  "-Ddd.feature.flags.configuration.source.agentless.base.url=" + endpoint,
                  "-Ddd.feature.flags.configuration.source.agentless.poll.interval.seconds=60",
                  "-Ddd.feature.flags.configuration.source.agentless.request.timeout.seconds=5",
                  "-Ddd.flagging.evaluation.counts.enabled=false",
                  "StandaloneOpenFeatureApp")
              .redirectErrorStream(true)
              .start();

      assertTrue(process.waitFor(15, SECONDS), "standalone application did not exit");
      final String output = new String(process.getInputStream().readAllBytes(), UTF_8);
      assertEquals(
          0,
          process.exitValue(),
          output + System.lineSeparator() + "configuration requests=" + configRequests.get());
      assertTrue(output.contains("RESULT=treatment"), output);
      assertTrue(configRequests.get() > 0, "standalone runtime did not poll configuration");
    } finally {
      server.stop(0);
    }
  }

  private static String classLocation(final Class<?> type) throws Exception {
    return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  }

  private static String javaBinary() {
    return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
  }
}
