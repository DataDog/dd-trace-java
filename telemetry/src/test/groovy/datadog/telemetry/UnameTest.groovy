package datadog.telemetry

import org.junit.Assume
import spock.lang.Specification

class UnameTest extends Specification {
  void 'can obtain the data via libc'() {
    Assume.assumeTrue('uname -a'.execute().waitFor() == 0)

    expect:
    Uname.UTS_NAME != null
    Uname.UTS_NAME.machine() == 'uname -m'.execute().text.trim()
    Uname.UTS_NAME.nodename() == 'uname -n'.execute().text.trim()
    Uname.UTS_NAME.release() == 'uname -r'.execute().text.trim()
    Uname.UTS_NAME.version() == 'uname -v'.execute().text.trim()
    if (System.getProperty('os.name') == 'Linux') {
      assert Uname.UTS_NAME.sysname() == 'Linux'
    } else {
      assert Uname.UTS_NAME.sysname().size() > 0
    }
  }
}
