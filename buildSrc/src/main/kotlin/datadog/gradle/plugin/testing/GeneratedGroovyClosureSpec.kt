package datadog.gradle.plugin.testing

import org.gradle.api.file.FileTreeElement
import org.gradle.api.specs.Spec
import org.objectweb.asm.ClassReader
import java.io.IOException
import java.io.Serializable

/** Excludes positively identified compiler-generated closures from test discovery. */
class GeneratedGroovyClosureSpec :
  Spec<FileTreeElement>,
  Serializable {
  override fun isSatisfiedBy(element: FileTreeElement): Boolean {
    if (element.isDirectory || !element.name.endsWith(".class")) return false
    return try {
      element.file.inputStream().use {
        val reader = ClassReader(it)
        reader.readInt(0) == 0xCAFEBABE.toInt() &&
          reader.superName == "groovy/lang/Closure" &&
          reader.interfaces.contains("org/codehaus/groovy/runtime/GeneratedClosure")
      }
    } catch (_: IOException) {
      false
    } catch (_: IllegalArgumentException) {
      false
    } catch (_: IndexOutOfBoundsException) {
      false
    }
  }
}
