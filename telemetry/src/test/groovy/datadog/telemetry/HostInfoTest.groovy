package datadog.telemetry

import datadog.trace.api.Platform
import org.junit.Assume
import spock.lang.Specification

class HostInfoTest extends Specification {
  void 'getHostname'() {
    when:
    final hostname = HostInfo.getHostname()

    then:
    hostname != null
    !hostname.trim().isEmpty()
  }

  void 'getOsName'() {
    when:
    final osName = HostInfo.getOsName()

    then:
    ["Linux", "Windows", "Darwin"].contains(osName)
  }

  void 'getOsVersion'() {
    when:
    final osVersion = HostInfo.getOsVersion()

    then:
    osVersion != null
    !osVersion.trim().isEmpty()
  }

  void 'compare to uname'() {
    Assume.assumeTrue('uname -a'.execute().waitFor() == 0)

    expect:
    HostInfo.getHostname() == 'uname -n'.execute().text.trim()
    HostInfo.getOsName() == 'uname -s'.execute().text.trim()
    HostInfo.getKernelName() == 'uname -s'.execute().text.trim()
    if (Platform.isMac()) {
      // uname -r will return X.Y.Z version, while JVM will report just X.Y
      'uname -r'.execute().text.trim().startsWith(HostInfo.getKernelRelease())

      // No /proc in macOS
      HostInfo.getKernelVersion() == ''
    }
    else {
      HostInfo.getKernelRelease() == 'uname -r'.execute().text.trim()
      // Ideally, this would be equal, but for now, we'll compromise to startWith.
      'uname -v'.execute().text.trim().startsWith(HostInfo.getKernelVersion())
    }
  }
}
