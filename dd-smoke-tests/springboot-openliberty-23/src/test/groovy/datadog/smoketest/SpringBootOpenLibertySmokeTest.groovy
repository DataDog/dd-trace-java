package datadog.smoketest

import datadog.environment.JavaVirtualMachine
import datadog.trace.test.util.ThreadUtils
import okhttp3.FormBody
import okhttp3.Request
import spock.lang.Requires
import spock.lang.Shared

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// This test currently fails on IBM JVMs
@Requires({ !JavaVirtualMachine.isJ9() })
class SpringBootOpenLibertySmokeTest extends AbstractServerSmokeTest {

  @Shared
  int totalInvocations = 100

  @Shared
  String openLibertyShadowJar = System.getProperty("datadog.smoketest.openliberty.jar.path")

  @Override
  ProcessBuilder createProcessBuilder() {
    // Make a copy of the OpenLiberty runnable JAR before injecting JVM configuration
    def applicationJar = copyApplicationJar().toAbsolutePath().toString()

    List<String> command = [
      javaPath(),
      "-jar",
      applicationJar,
      "--server.port=${httpPort}" as String
    ]

    List<String> jvmOptions = new ArrayList<>()
    jvmOptions.addAll(defaultJavaProperties)
    jvmOptions.addAll([
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()}:includeService,DDAgentWriter" as String,
      "-Ddd.jmxfetch.enabled=false",
      "-Ddd.appsec.enabled=true",
      "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
      "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
      "-Ddd.iast.enabled=true",
      "-Ddd.iast.request-sampling=100",
      "-Ddd.integration.spring-boot.enabled=true"
    ])
    injectOpenLibertyJvmOptions(applicationJar, jvmOptions)

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.environment().put('WLP_JAR_EXTRACT_ROOT', 'application')
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }

  Path copyApplicationJar() {
    def applicationJar = Paths.get(openLibertyShadowJar)
    def randomId = System.nanoTime()
    def uniqueName = applicationJar.fileName.toString()
    uniqueName = uniqueName.substring(0, uniqueName.length() - 4) + "-${randomId}.jar"
    def specificationJar = applicationJar.parent.parent.resolve(uniqueName)
    Files.copy(applicationJar, specificationJar)
    return specificationJar
  }

  void injectOpenLibertyJvmOptions(String applicationJar, List<String> options) {
    def appUri = URI.create("jar:file:$applicationJar")
    try (def fs = FileSystems.newFileSystem(appUri, [:])) {
      def jvmOptionFile = fs.getPath( 'wlp', 'usr', 'servers', 'defaultServer', 'jvm.options')
      try (def writer = Files.newBufferedWriter(jvmOptionFile)) {
        options.each {
          writer.write(it)
          writer.newLine()
        }
      }
    }
  }

  @Override
  def inferServiceName() {
    false // will use spring properties
  }

  @Override
  File createTemporaryFile() {
    return new File("${buildDirectory}/tmp/springboot-openliberty.out")
  }

  @Override
  protected Set<String> expectedTraces() {
    return [
      "[smoke-test:servlet.request[smoke-test:spring.handler[smoke-test:http.request]]]",
      "[smoke-test:servlet.request[smoke-test:spring.handler]]"
    ].toSet()
  }

  @Override
  boolean testTelemetry() {
    false
  }

  def "Test concurrent requests to Spring Boot running Open Liberty"() {
    setup:
    def url = "http://localhost:${httpPort}/connect"
    def request = new Request.Builder().url(url).get().build()

    expect:
    ThreadUtils.runConcurrently(10, totalInvocations, {
      def response = client.newCall(request).execute()

      assert response.body().string() != null
      assert response.body().contentType().toString().contains("text/plain")
      assert response.code() == 200
    })

    waitForTraceCount(2 * totalInvocations) == 2 * totalInvocations
  }

  def "Test concurrent high load requests to Spring Boot running Open Liberty"() {
    def url = "http://localhost:${httpPort}/connect/0"
    def formBody = new FormBody.Builder()
    def value = "not too big!"
    for (int i = 0; i < 100; i++) {
      formBody.add("test" + i, value)
    }
    def request = new Request.Builder().url(url).post(formBody.build()).build()

    expect:
    ThreadUtils.runConcurrently(10, totalInvocations, {
      def response = client.newCall(request).execute()

      assert response.body().string() != null
      assert response.body().contentType().toString().contains("text/plain")
      assert response.code() == 200
    })

    waitForTraceCount(totalInvocations) == totalInvocations
  }
}
