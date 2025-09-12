package datadog.gradle.plugin.muzzle


import org.eclipse.aether.version.Version
import spock.lang.Specification

class VersionSetTest extends Specification {

  def "parse versions properly"() {
    when:
    def parsed = new VersionSet.ParsedVersion(version)

    then:
    parsed.versionNumber == versionNumber
    parsed.ending == ending
    parsed.majorMinor == versionNumber >> VersionSet.ParsedVersion.VERSION_SHIFT

    where:
    version                 | versionNumber   | ending
    ver('1.2.3')            | num(1, 2, 3)    | ""
    ver('4.5.6-foo')        | num(4, 5, 6)    | "foo"
    ver('7.8.9.foo')        | num(7, 8, 9)    | "foo"
    ver('10.11.12.foo-bar') | num(10, 11, 12) | "foo-bar"
    ver('13.14.foo-bar')    | num(13, 14, 0)  | "foo-bar"
    ver('15.foo')           | num(15, 0, 0)   | "foo"
    ver('16-foo')           | num(16, 0, 0)   | "foo"
  }

  def "select low and high from major.minor"() {
    when:
    def versionSet = new VersionSet(versions)

    then:
    versionSet.lowAndHighForMajorMinor == expected

    where:
    versions << [[
      ver('4.5.6'),
      ver('1.2.3')
    ], [
      ver('1.2.3'),
      ver('1.2.1'),
      ver('1.3.0'),
      ver('1.2.7'),
      ver('1.4.17'),
      ver('1.4.1'),
      ver('1.4.0'),
      ver('1.4.10')
    ]]
    expected << [[
      ver('1.2.3'),
      ver('4.5.6')
    ], [
      ver('1.2.1'),
      ver('1.2.7'),
      ver('1.3.0'),
      ver('1.4.0'),
      ver('1.4.17')
    ]]
  }

  Version ver(String string) {
    return new TestVersion(string)
  }

  long num(int major, int minor, int micro) {
    long result = major
    return (((result << 12) + minor) << 12) + micro
  }

  static class TestVersion implements Version {
    private final String string

    TestVersion(String versionString) {
      this.string = versionString
    }

    @Override
    int compareTo(Version o) {
      if (o == null) return 1
      if (! o instanceof TestVersion) return 1
      TestVersion other = o as TestVersion
      return string <=> other.string
    }

    @Override
    String toString() {
      return string
    }
  }
}
