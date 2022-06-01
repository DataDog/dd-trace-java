package com.datadog.debugger.agent

import scala.reflect.internal.util.{AbstractFileClassLoader, BatchSourceFile}
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.{Global, Settings}

object ScalaHelper {
  def compileAndLoad(source: String, className: String, sourceName: String) = {
    val settings = new Settings()
    settings.usejavacp.value = true
    val virDir = new VirtualDirectory("(memory)", None)
    settings.outputDirs.setSingleOutput(virDir)
    val global = new Global(settings)
    val run    = new global.Run()
    run.compileSources(List(new BatchSourceFile(sourceName, source)))
    val classLoader =
      new AbstractFileClassLoader(virDir, this.getClass.getClassLoader)
    classLoader.loadClass(className)
  }
}
