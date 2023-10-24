package datadog.smoketest

class KnopflerfishSmokeTest extends AbstractOSGiSmokeTest {

  String frameworkJar() {
    return System.getProperty("datadog.smoketest.osgi.knopflerfishJar.path")
  }

  List<String> frameworkArguments() {
    return ["-Dframework.factory=org.knopflerfish.framework.FrameworkFactoryImpl", "-Ddd.trace.debug=true"
            //, "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
    ]
  }
}
