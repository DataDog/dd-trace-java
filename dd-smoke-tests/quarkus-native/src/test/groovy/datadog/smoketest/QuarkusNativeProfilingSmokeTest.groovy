package datadog.smoketest

import okhttp3.Request
import org.openjdk.jmc.common.item.ItemFilters
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit
import org.openjdk.jmc.flightrecorder.internal.InvalidJfrFileException
import spock.lang.Shared
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger

/**
 * Smoke test for profiling in Quarkus native images.
 *
 * This test validates that profiling works correctly when the Datadog agent is attached
 * during GraalVM native-image compilation. The build will fail if any profiler classes
 * have incorrect native-image configuration (e.g., missing reflection metadata or
 * improper class initialization timing).
 *
 * Test validates:
 * 1. Native image builds successfully with profiling enabled
 * 2. Application starts without agent initialization errors
 * 3. Profiler produces JFR files at runtime
 */
class QuarkusNativeProfilingSmokeTest extends QuarkusSlf4jSmokeTest {

  @Shared
  @TempDir
  Path testJfrDir

  @Override
  protected List<String> additionalArguments() {
    return [
      "-Ddd.profiling.upload.period=1",
      "-Ddd.profiling.start-force-first=true",
      "-Ddd.profiling.debug.dump_path=${testJfrDir}".toString(),
      // TODO: Remove this arg after JFR initialization is fixed on GraalVM 25.
      // https://datadoghq.atlassian.net/browse/PROF-12742
      "-XX:StartFlightRecording=filename=${testJfrDir}/recording.jfr".toString(),
    ]
  }

  def "profiling works in native image"() {
    setup:
    String url = "http://localhost:${httpPort}/${endpointName}?id=1"
    def conditions = new PollingConditions(initialDelay: 2, timeout: 10)

    when:
    // Make a request to generate some activity for the profiler
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.code() == 200
    response.body().string() == "Hello 1!"

    // Wait for JFR files with execution samples to be generated
    conditions.eventually {
      assert countJfrsWithExecutionSamples() > 0
    }
  }

  int countJfrsWithExecutionSamples() {
    AtomicInteger jfrCount = new AtomicInteger(0)
    Files.walkFileTree(testJfrDir, new SimpleFileVisitor<Path>() {
        @Override
        FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (file.toString().endsWith(".jfr")) {
            try {
              def events = JfrLoaderToolkit.loadEvents(file.toFile())
              if (events.apply(ItemFilters.type("jdk.ExecutionSample")).hasItems()) {
                jfrCount.incrementAndGet()
                return FileVisitResult.SKIP_SIBLINGS
              }
            } catch (InvalidJfrFileException ignored) {
              // The recording captured at process exit might be incomplete
            }
          }
          return FileVisitResult.CONTINUE
        }
      })
    return jfrCount.get()
  }
}
