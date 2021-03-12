package datadog.smoketest

class FelixSmokeTest extends AbstractOSGiSmokeTest {

  String frameworkJar() {
    return System.getProperty("datadog.smoketest.osgi.felixJar.path")
  }

  List<String> frameworkArguments() {
    return [
      "-Dframework.factory=org.apache.felix.framework.FrameworkFactory",
      "-Dfelix.bootdelegation.implicit=false"
    ]
  }
}
