package datadog.trace.instrumentation.play25

object Util {
  implicit class MapExtensions[A](m: Iterable[(String, A)]) {
    def toStringAsGroovy: String = {
      def valueToString(value: Object): String = value match {
        case seq: Seq[_] =>
          seq.map(x => valueToString(x.asInstanceOf[Object])).mkString("[", ",", "]")
        case other => other.toString
      }

      m.map { case (key, value) => s"$key:${valueToString(value.asInstanceOf[Object])}" }
        .mkString("[", ",", "]")
    }
  }

  def createCustomException(msg: String): Exception = {
    val clazz = Class.forName(
      "datadog.trace.instrumentation.play25.server.TestHttpErrorHandler$CustomRuntimeException"
    )
    val constructor = clazz.getConstructor(classOf[String])
    constructor.newInstance(msg).asInstanceOf[Exception]
  }
}
