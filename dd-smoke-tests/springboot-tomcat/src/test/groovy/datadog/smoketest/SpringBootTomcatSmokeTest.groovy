package datadog.smoketest

import okhttp3.Request
import spock.lang.Shared

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

class SpringBootTomcatSmokeTest extends AbstractServerSmokeTest {

  @Shared
  def tomcatDirectory = new File(System.getProperty("datadog.smoketest.tomcatDir")).toPath()

  @Shared

  def springBootShadowWar = new File(System.getProperty("datadog.smoketest.springboot.war.path")).toPath()

  @Override
  protected void beforeProcessBuilders() {
    try {
      def catalinaPath = tomcatDirectory.resolve("bin/catalina.sh")
      def permissions = new HashSet<>(Files.getPosixFilePermissions(catalinaPath))
      permissions.add(PosixFilePermission.OWNER_EXECUTE)
      Files.setPosixFilePermissions(catalinaPath, permissions)
    } catch (Exception ignored) {
      // not posix ... continue
    }
    Files.copy(springBootShadowWar, tomcatDirectory.resolve("webapps/smoke.war"), StandardCopyOption.REPLACE_EXISTING)
    def tomcatServerConfPath = tomcatDirectory.resolve("conf/server.xml")
    Files.write(tomcatServerConfPath, new String(Files.readAllBytes(tomcatServerConfPath), StandardCharsets.UTF_8)
      .replace("<Connector port=\"8080\" protocol=\"HTTP/1.1\"",
      "<Connector port=\"$httpPort\" protocol=\"HTTP/1.1\"")
      .getBytes(StandardCharsets.UTF_8))
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    ProcessBuilder processBuilder =
      new ProcessBuilder("bin/catalina.sh", "run")
    processBuilder.directory(tomcatDirectory.toFile())
    List<String> catalinaOpts = [
      *defaultJavaProperties,
      "-Ddd.writer.type=TraceStructureWriter:${output.getAbsolutePath()}:includeService:includeResource",
      "-Ddd.integration.spring-boot.enabled=true"
    ]
    processBuilder.environment().put("CATALINA_OPTS", catalinaOpts.collect({ it.replace(' ', '\\ ')}).join(" "))
    return processBuilder
  }

  @Override
  protected File createTemporaryFile() {
    return File.createTempFile("trace-structure-SpringBootTomcatSmokeTest", "out")
  }

  @Override
  def inferServiceName() {
    false // will use servlet context
  }

  @Override
  protected Set<String> expectedTraces() {
    return ["[smoke:servlet.request:GET /hello[smoke:spring.handler:TestSuite.hello]]"].toSet()
  }

  @Override
  def cleanupSpec() {
    ProcessBuilder processBuilder =
      new ProcessBuilder("bin/catalina.sh", "stop")
    processBuilder.directory(tomcatDirectory.toFile())
    Process process = processBuilder.start()
    process.waitFor()
  }

  def "default home page #n th time"() {
    setup:
    String url = "http://localhost:${httpPort}/smoke/hello"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contentEquals("world")
    response.body().contentType().toString().contains("text/plain")
    response.code() == 200

    where:
    n << (1..200)
  }
}
