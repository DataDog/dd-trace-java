package generator

import play.routes.compiler.{InjectedRoutesGenerator, RoutesCompiler}

import java.io.File
import scala.collection.immutable

object CompileRoutes extends App {
  val routesFile     = args(0)
  val destinationDir = args(1)

  val routesCompilerTask =
    RoutesCompiler.RoutesCompilerTask(new File(routesFile), immutable.Seq.empty, true, true, false)
  RoutesCompiler.compile(routesCompilerTask, InjectedRoutesGenerator, new File(destinationDir))
}
