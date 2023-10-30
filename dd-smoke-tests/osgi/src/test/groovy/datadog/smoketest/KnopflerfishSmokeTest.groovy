package datadog.smoketest

class KnopflerfishSmokeTest extends AbstractOSGiSmokeTest {

  String frameworkJar() {
    return System.getProperty("datadog.smoketest.osgi.knopflerfishJar.path")
  }

  List<String> frameworkArguments() {
    return ["-Dframework.factory=org.knopflerfish.framework.FrameworkFactoryImpl",]
  }
}
