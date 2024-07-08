import gradle.kotlin.dsl.accessors._3908dec5f52cdaf4b0a666e9f9731da2.sourceSets
import gradle.kotlin.dsl.accessors._5c4f8bbe3a62f1bcdc0caabeff2db4ed.ext
import org.gradle.api.Project
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.internal.impldep.org.eclipse.jgit.attributes.FilterCommandRegistry.register
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.`jvm-test-suite`


// TODO How OTel fixed this: https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/e853afe2c7de27268f5aec032ddbc8dfa9ad10e2/conventions/src/main/kotlin/io.opentelemetry.instrumentation.base.gradle.kts

plugins {
  java
  `jvm-test-suite`
}

val addTestSuiteExtendingForDir: (String, String, String) -> Unit = {testSuiteName, parentSuiteName, dirName ->
  testing {
    suites {
      register<JvmTestSuite>(testSuiteName) {
        sources {
          java {
            setSrcDirs(listOf("src/$dirName/java"))
          }
          resources {
            setSrcDirs(listOf("src/$dirName/resources"))
          }
//          if (project.plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
            "kotlin" {
              setSrcDir(listOf("src/$dirName/kotlin"))
            }
//          }
        }

        dependencies {
          implementation(project())
        }
      }
    }
  }

  // TODO Port configurations too
}

ext.set("addTestSuiteExtendingForDir", addTestSuiteExtendingForDir)
ext.set("addTestSuiteForDir", fun (testSuiteName: String, dirName: String) = addTestSuiteExtendingForDir(testSuiteName, "test", dirName))


fun myCustomMethod(value: String) = println("MyCustomMethod $value")
ext.set("myCustomMethod", ::myCustomMethod)
//ext.set("myCustomMethod", fun(value: String): Unit { println("MyCustomMethod $value") })

//ext.addTestSuiteForDir = Project::addTestSuiteForDir
//extra["addTestSuiteForDir"] = fun(testSuiteName: String, dirName: String) {
//  testing {
//    suites {
//      create("test", JvmTestSuite::class.java) {
//
//      }
//    }
//  }
//}

