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

  def "test parse #version yields #major, #minor, and #update"() {
    when:
    def javaVersion = Platform.parseJavaVersion(version)
    then:
    major == javaVersion.major
    minor == javaVersion.minor
    update == javaVersion.update

    where:
    version     | major | minor | update
    ""          | 0     | 0     | 0
    "a.0.0"     | 0     | 0     | 0
    "0.a.0"     | 0     | 0     | 0
    "0.0.a"     | 0     | 0     | 0
    "1.a.0_0"   | 0     | 0     | 0
    "1.8.a_0"   | 0     | 0     | 0
    "1.8.0_a"   | 0     | 0     | 0
    "1.7"       | 7     | 0     | 0
    "1.7.0"     | 7     | 0     | 0
    "1.7.0_221" | 7     | 0     | 221
    "1.8"       | 8     | 0     | 0
    "1.8.0"     | 8     | 0     | 0
    "1.8.0_212" | 8     | 0     | 212
    "1.8.0_292" | 8     | 0     | 292
    "9-ea"      | 9     | 0     | 0
    "9.0.4"     | 9     | 0     | 4
    "9.1.2"     | 9     | 1     | 2
    "10.0.2"    | 10    | 0     | 2
    "11"        | 11    | 0     | 0
    "11.0.6"    | 11    | 0     | 6
    "11.0.11"   | 11    | 0     | 11
    "12.0.2"    | 12    | 0     | 2
    "13.0.2"    | 13    | 0     | 2
    "14"        | 14    | 0     | 0
    "14.0.2"    | 14    | 0     | 2
    "15"        | 15    | 0     | 0
    "15.0.2"    | 15    | 0     | 2
    "16.0.1"    | 16    | 0     | 1
  }
}
