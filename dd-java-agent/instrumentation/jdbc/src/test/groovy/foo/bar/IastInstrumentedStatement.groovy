package foo.bar

import groovy.transform.CompileStatic

import java.sql.Statement

@CompileStatic
class IastInstrumentedStatement implements Statement {
  @Delegate
  Statement stmt
}
