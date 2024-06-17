package datadog.smoketest.appsec

abstract class AbstractSpringBootWithGRPCAppSecTest extends AbstractAppSecServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot-grpc.shadowJar.path")
    assert springBootShadowJar != null

    List<String> command = [
      javaPath(),
      *defaultJavaProperties,
      *defaultAppSecProperties,
      "-jar",
      springBootShadowJar,
      "--server.port=${httpPort}"
    ].collect { it as String }

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  static final String ROUTE = 'async_annotation_greeting'
}
