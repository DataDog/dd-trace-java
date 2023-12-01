import datadog.smoketest.AbstractServerSmokeTest
import okhttp3.Request
import spock.lang.Shared
import spock.lang.TempDir

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
      "-Ddd.profiling.debug.dump_path=${testJfrDir}"
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
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("Hello world")
    waitForTraceCount(1)

    // sanity test for profiler generating JFR files
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
            jfrCount.incrementAndGet()
          }
          return FileVisitResult.CONTINUE
        }
      })
    return jfrCount.get()
  }
}
