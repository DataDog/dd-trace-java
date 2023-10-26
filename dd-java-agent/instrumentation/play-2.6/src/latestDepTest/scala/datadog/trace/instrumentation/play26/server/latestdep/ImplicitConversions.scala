package datadog.trace.instrumentation.play26.server.latestdep

import scala.collection.Seq

object ImplicitConversions {
  implicit class MapExtensions[A](m: Iterable[(String, A)]) {
    def toStringAsGroovy: String = {
      def valueToString(value: Object) : String = value match {
        case seq: Seq[_] => seq.map(x => valueToString(x.asInstanceOf[Object])).mkString("[", ",", "]")
        case other => other.toString
      }

      m.map { case (key, value) => s"$key:${valueToString(value.asInstanceOf[Object])}" }
        .mkString("[", ",", "]")
    }
  }
}
