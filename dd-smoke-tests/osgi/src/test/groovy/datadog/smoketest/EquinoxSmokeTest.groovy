package datadog.smoketest

class EquinoxSmokeTest extends AbstractOSGiSmokeTest {

  String frameworkJar() {
    return System.getProperty("datadog.smoketest.osgi.equinoxJar.path")
  }

  List<String> frameworkArguments() {
    return ["-Dframework.factory=org.eclipse.osgi.launch.EquinoxFactory"]
  }
}
