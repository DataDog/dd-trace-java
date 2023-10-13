package foo.bar

import groovy.transform.CompileStatic

import java.sql.Connection

@CompileStatic
class IastInstrumentedConnection implements Connection {
  @Delegate
  Connection conn
}
