package datadog.trace.civisibility.source.index

class ScalaClass {}

class ScalaChildClass extends ScalaClass {}

case class ScalaCaseClass(s: String) {}

case class ScalaChildCaseClass(s: String) extends ScalaClass {}

object ScalaObject

object ScalaChildObject extends ScalaClass {}

case object ScalaCaseObject

case object ScalaChildCaseObject extends ScalaClass {}

trait ScalaTrait

trait ScalaChildTrait extends ScalaTrait
