package datadog.trace.test.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class FileUtils {
  // Use this for writing a string directly into a file
  static writeFileRaw(Path filePath, String data) {
    StandardOpenOption[] openOpts = [StandardOpenOption.WRITE] as StandardOpenOption[]
    Files.write(filePath, data.getBytes(), openOpts)
  }

  static Path tempFile() {
    try {
      return Files.createTempFile("testFile_", ".yaml")
    } catch (IOException e) {
      println "Error creating file: ${e.message}"
      e.printStackTrace()
      return null
    }
  }
}
