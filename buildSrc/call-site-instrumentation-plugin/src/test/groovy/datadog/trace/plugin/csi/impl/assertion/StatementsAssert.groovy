package datadog.trace.plugin.csi.impl.assertion

import com.github.javaparser.ast.stmt.Statement

import java.util.function.Consumer

class StatementsAssert {

  private List<Statement> statements

  void asString(String... values) {
    final asList = this.statements*.toString()
    assert asList == values.toList()
  }

  void statement(int index, String value) {
    final stringValue = this.statements[index].toString()
    assert stringValue == value
  }

  void statement(int index, Consumer<Statement> predicate) {
    predicate.accept(this.statements[index])
  }
}

