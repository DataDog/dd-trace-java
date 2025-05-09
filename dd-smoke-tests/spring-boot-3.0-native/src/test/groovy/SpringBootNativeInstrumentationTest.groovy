import datadog.smoketest.AbstractServerSmokeTest
import okhttp3.Request
import org.openjdk.jmc.common.item.IItemCollection
import org.openjdk.jmc.common.item.ItemFilters
import org.openjdk.jmc.flightrecorder.internal.InvalidJfrFileException
import spock.lang.Shared
import spock.lang.TempDir

import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit
import spock.util.concurrent.PollingConditions

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger

class SpringBootNativeInstrumentationTest extends AbstractServerSmokeTest {
  @Shared
  @TempDir
  def testJfrDir

  @Override
  ProcessBuilder createProcessBuilder() {
    String springNativeExecutable = System.getProperty('datadog.smoketest.spring.native.executable')

    List<String> command = new ArrayList<>()
    command.add(springNativeExecutable)
    command.addAll(nativeJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter",
      // trigger use of moshi for parsing sampling rules
      '-Ddd.trace.sampling.rules=[]',
      '-Ddd.span.sampling.rules=[]',
      // enable improved trace.annotation span names
      '-Ddd.trace.annotations.legacy.tracing.enabled=false',
      "--server.port=${httpPort}",
      '-Ddd.profiling.upload.period=1',
      '-Ddd.profiling.start-force-first=true',
      "-Ddd.profiling.debug.dump_path=${testJfrDir}",
      "-Ddd.integration.spring-boot.enabled=true"
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  File createTemporaryFile() {
    return File.createTempFile('trace-structure-docs', 'out')
  }

  @Override
  protected Set<String> expectedTraces() {
    return ["[servlet.request[spring.handler[WebController.doHello[WebController.sayHello]]]]"]
  }

  @Override
  boolean testTelemetry() {
    false
  }

  @Override
  boolean isErrorLog(String log) {
    // Check that there are no ClassNotFound errors printed from bad reflect-config.json
    super.isErrorLog(log) || log.contains("ClassNotFoundException")
  }

  def "check native instrumentation"() {
    setup:
    String url = "http://localhost:${httpPort}/hello"
    def conditions = new PollingConditions(initialDelay: 2, timeout: 6)

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("Hello world")
    waitForTraceCount(1)

    conditions.eventually {
      assert countJfrs() > 0
    }

    udpMessage.get(1, TimeUnit.SECONDS) contains "service:smoke-test-java-app,version:99,env:smoketest"
  }

  int countJfrs() {
    AtomicInteger jfrCount = new AtomicInteger(0)
    Files.walkFileTree(testJfrDir, new SimpleFileVisitor<Path>() {
        @Override
        FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
          return FileVisitResult.CONTINUE
        }

        @Override
        FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (file.toString().endsWith(".jfr")) {
            try {
              IItemCollection events = JfrLoaderToolkit.loadEvents(file.toFile())
              if (events.apply(ItemFilters.type("jdk.ExecutionSample")).hasItems()) {
                jfrCount.incrementAndGet()
                return FileVisitResult.SKIP_SIBLINGS
              }
            } catch (InvalidJfrFileException ignored) {
              // the recording captured at process exit might be incomplete
            }
          }
          return FileVisitResult.CONTINUE
        }
      })
    return jfrCount.get()
  }
}
