//plugins {
//  id("com.diffplug.spotless")
//}

//spotless {
//  if (project.plugins.hasPlugin("kotlin")) {
//    kotlin {
//      toggleOffOn()
//      // ktfmt('0.40').kotlinlangStyle() // needs Java 11+
//      // Newer versions do not work well with the older version of kotlin in this build
//      ktlint("0.41.0").userData(mapOf("indent_size" to "2", "continuation_indent_size" to "2"))
//    }
//  }
//}
