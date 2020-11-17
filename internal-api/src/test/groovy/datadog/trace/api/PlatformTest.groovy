package datadog.trace.api

import datadog.trace.test.util.DDSpecification
import org.junit.Assume

class PlatformTest extends DDSpecification {

  def "is at least java 7"() {
    expect: Platform.isJavaVersionAtLeast(7)
  }

  def "is at least java 8 when run on JDK1.8 or later"() {
    Assume.assumeTrue(!System.getProperty("java.version").startsWith("1.")
      || System.getProperty("java.version").startsWith("1.8."))
    expect: Platform.isJavaVersionAtLeast(8) && Platform.isJavaVersionAtLeast(7)
  }

  def "is at least java 11 when run on JDK11 or later"() {
    Assume.assumeTrue(!System.getProperty("java.version").startsWith("1.")
    && !(System.getProperty("java.version").startsWith("9.")
      || System.getProperty("java.version").startsWith("10.")))
    expect: Platform.isJavaVersionAtLeast(11) && Platform.isJavaVersionAtLeast(8)
  }

  def "test parse #version yields #expected"() {
    when:
    int parsedVersion = Platform.parseJavaVersion(version)
    then:
    expected == parsedVersion

    where:
    version     | expected
    "1.7"       | 7
    "1.7.0"     | 7
    "1.8"       | 8
    "1.8.0"     | 8
    "1.8.0_212" | 8
    "9-ea"      | 9
    "9.1.2"     | 9
    "11"        | 11
    "11.0.6"    | 11
    "14"        | 14
    "15"        | 15
  }

}
