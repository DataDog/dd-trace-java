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
    javaVersion.is(major)
    javaVersion.is(major, minor)
    javaVersion.is(major, minor, update)
    javaVersion.isAtLeast(major, minor, update)
    javaVersion.isBetween(major, minor, update, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
    !javaVersion.isBetween(major, minor, update, major, minor, update)
    !javaVersion.isBetween(major, minor, update, major - 1, 0, 0)
    !javaVersion.isBetween(major, minor, update, major, minor -1, 0)
    !javaVersion.isBetween(major, minor, update, major, minor, update - 1)
    javaVersion.isBetween(major, minor, update, major + 1, 0, 0)
    javaVersion.isBetween(major, minor, update, major, minor + 1, 0)
    javaVersion.isBetween(major, minor, update, major, minor, update + 1)

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
    "11.0.9.1+1"| 11    | 0     | 9
    "11.0.6+10" | 11    | 0     | 6
    "17.0.4-x"  | 17    | 0     | 4
  }

  def "test parse #version is at least #major, #minor, and #update"() {
    when:
    def javaVersion = Platform.parseJavaVersion(version)
    then:
    javaVersion.isAtLeast(major, minor, update)

    where:
    version     | major | minor | update
    "17.0.5+8"  | 17    | 0     | 5
    "17.0.5"    | 17    | 0     | 5
    "17.0.6+8"  | 17    | 0     | 5
    "11.0.17+8" | 11    | 0     | 17
    "11.0.18+8" | 11    | 0     | 17
    "11.0.17"   | 11    | 0     | 17
    "1.8.0_352" | 8     | 0     | 352
    "1.8.0_362" | 8     | 0     | 352
  }

  def "JVMRuntime is at least a bit resilient against weird version properties"() {
    when:
    def runtime = new Platform.JvmRuntime(propVersion, rtVersion, propName, propVendor, null)

    then:
    runtime.version == version
    runtime.patches == patch
    runtime.name == name
    runtime.vendor == vendor

    where:
    propVersion | rtVersion       | propName         | propVendor     | version     | patch | name             | vendor
    '1.8.0_265' | '1.8.0_265-b01' | 'OpenJDK'        | 'AdoptOpenJDK' | '1.8.0_265' | 'b01' | 'OpenJDK'        | 'AdoptOpenJDK'
    '1.8.0_265' | '1.8-b01'       | 'OpenJDK'        | 'AdoptOpenJDK' | '1.8.0_265' | ''    | 'OpenJDK'        | 'AdoptOpenJDK'
    '19'        | '19'            | 'OpenJDK 64-Bit' | 'Homebrew'     | '19'        | ''    | 'OpenJDK 64-Bit' | 'Homebrew'
    '17'        | null            | null             | null           | '17'        | ''    | ''               | ''
    null        | '17'            | null             | null           | ''          | ''    | ''               | ''
  }
}
