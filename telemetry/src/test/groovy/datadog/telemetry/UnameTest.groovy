package datadog.telemetry

import spock.lang.Specification

class UnameTest extends Specification {
  void 'can obtain the data via libc'() {
    expect:
    Uname.UTS_NAME != null
    Uname.UTS_NAME.machine().size() > 0
    Uname.UTS_NAME.nodename().size() > 0
    Uname.UTS_NAME.release().size() > 0
    Uname.UTS_NAME.version().size() > 0
    if (System.getProperty('os.name') == 'Linux') {
      assert Uname.UTS_NAME.sysname() == 'Linux'
    } else {
      assert Uname.UTS_NAME.sysname().size() > 0
    }
  }
}
