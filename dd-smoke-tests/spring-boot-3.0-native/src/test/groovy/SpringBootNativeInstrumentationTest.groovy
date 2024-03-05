import datadog.smoketest.AbstractServerSmokeTest
import okhttp3.Request
import org.openjdk.jmc.common.item.IItemCollection
import org.openjdk.jmc.common.item.ItemFilters
import org.openjdk.jmc.flightrecorder.internal.InvalidJfrFileException
import spock.lang.Shared
import spock.lang.TempDir

import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

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

  def "check native instrumentation"() {
    setup:
    String url = "http://localhost:${httpPort}/hello"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    def ts = System.nanoTime()
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("Hello world")
    waitForTraceCount(1)

    // sanity test for profiler generating JFR files
    // the recording is collected after 1 second of execution
    // make sure the app has been up and running for at least 1.5 seconds
    while (System.nanoTime() - ts < 1_500_000_000L) {
      LockSupport.parkNanos(1_000_000)
    }
    countJfrs() > 0

    when:
    checkLogPostExit {
      // Check that there are no ClassNotFound errors printed from bad reflect-config.json
      if (it.contains("ClassNotFoundException")) {
        println "Found ClassNotFoundException in log: ${it}"
        logHasErrors = true
      }
    }

    then:
    !logHasErrors
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
