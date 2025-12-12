import org.gradle.api.plugins.jvm.JvmTestSuite

fun Project.addTestSuiteExtendingForDir(testSuiteName: String, parentSuiteName: String, dirName: String) {
  testing {
    suites {
      create(testSuiteName, JvmTestSuite::class) {
        sources {
          java {
            srcDirs("src/$dirName/java")
          }
          resources {
            srcDirs("src/$dirName/resources")
          }
          if (project.plugins.hasPlugin("groovy")) {
            groovy {
              srcDirs("src/$dirName/groovy")
            }
          }
          if (project.plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
            kotlin {
              srcDirs("src/$dirName/kotlin")
            }
          }
          if (project.plugins.hasPlugin("scala")) {
            scala {
              srcDirs("src/$dirName/scala")
            }
          }
        }
      }
    }
  }

  configurations {
    val extendConf = { suffix: String ->
      val config = named("${testSuiteName}${suffix}")
      val parentConfig = named("${parentSuiteName}${suffix}")
      if (parentConfig.isPresent) {
        config.configure {
          extendsFrom(parentConfig.get())
        }
      }

      if (testSuiteName.matches(Regex(".*ForkedTest$"))) {
        val nonForkedBaseConfName = testSuiteName.replaceFirst("Forked", "")
        val nonForkedConfig = maybeCreate("${nonForkedBaseConfName}${suffix}")
        config.configure {
          extendsFrom(nonForkedConfig)
        }
      }
    }

    extendConf("CompileOnly")
    extendConf("Implementation")
    extendConf("RuntimeOnly")
    extendConf("AnnotationProcessor")
  }

  tasks.register("${testSuiteName}Jar", Jar::class) {
    dependsOn(tasks.named("${testSuiteName}Classes"))
    from(sourceSets.named(testSuiteName).get().output)
    archiveClassifier.set(testSuiteName)
  }

  // The project dependency definition cannot sit inside previous blocks. As of Gradle 8.8, configurations cannot be mutated after they have been used as part of the dependency graph.
  // See: https://docs.gradle.org/current/userguide/upgrading_version_8.html#mutate_configuration_after_locking
  // And related issue: https://github.com/gradle/gradle/issues/28867
  dependencies {
    add("${testSuiteName}Implementation", project(project.path))
  }
}

fun Project.addTestSuiteForDir(testSuiteName: String, dirName: String) {
  addTestSuiteExtendingForDir(testSuiteName, "test", dirName)
}

fun Project.addTestSuite(testSuiteName: String) {
  addTestSuiteForDir(testSuiteName, testSuiteName)
}
