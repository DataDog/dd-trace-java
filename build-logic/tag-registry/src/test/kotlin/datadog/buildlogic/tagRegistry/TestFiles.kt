package datadog.buildlogic.tagRegistry

import org.intellij.lang.annotations.Language
import java.io.File

internal fun File.writeFile(path: String, vararg contents: String) = resolve(path).apply {
  parentFile.mkdirs()
  writeText(contents.joinToString("\n") { it.trimIndent() } + "\n")
}

internal fun File.conventionsFile(@Language("yaml") yamlText: String) = writeFile("tag-conventions.yaml", yamlText)
