plugins {
  java
  id("com.diffplug.spotless") version "8.4.0"
  id("com.gradleup.shadow") version "9.4.2"
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

spotless {
  java {
    toggleOffOn()
    // set explicit target to workaround https://github.com/diffplug/spotless/issues/1163
    target("src/**/*.java")
    // ignore embedded test projects
    targetExclude("src/test/resources/**")
    googleJavaFormat("1.35.0")
  }
}

apply {
  from("$rootDir/../gradle/repositories.gradle")
}

dependencies {
  compileOnly(libs.jsr305)

  implementation("org.freemarker", "freemarker", "2.3.30")
  implementation(libs.asm)
  implementation(libs.asm.tree)
  implementation(libs.javaparser.symbol.solver)

  testCompileOnly(libs.jsr305)
  testImplementation(libs.bytebuddy)
  testImplementation(libs.bundles.junit5)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.bundles.mockito)
  testImplementation("javax.servlet", "javax.servlet-api", "3.0.1")
}

sourceSets {
  test {
    java {
      srcDirs("src/test/java", "${layout.buildDirectory.get()}/generated/sources/csi")
    }
  }
}

tasks {
  val copyCallSiteSources = register<Copy>("copyCallSiteSources") {
    val csiPackage = "datadog/trace/agent/tooling/csi"
    val source = layout.projectDirectory.file("../../dd-java-agent/agent-tooling/src/main/java/$csiPackage")
    val target = layout.buildDirectory.dir("generated/sources/csi/$csiPackage")
    doFirst {
      val folder = target.get().asFile
      if (folder.exists() && !folder.deleteRecursively()) {
        throw GradleException("Cannot delete files in $folder")
      }
    }
    from(source)
    into(target)
    group = "build"
  }

  withType<AbstractCompile>().configureEach {
    dependsOn(copyCallSiteSources)
  }

  shadowJar {
    duplicatesStrategy = DuplicatesStrategy.FAIL
    mergeServiceFiles()
    // Service descriptors are intentionally merged by mergeServiceFiles(); let
    // duplicate service entries reach that transformer instead of failing first.
    filesMatching("META-INF/services/**") {
      duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    filesNotMatching("META-INF/services/**") {
      duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    manifest {
      attributes(mapOf("Main-Class" to "datadog.trace.plugin.csi.PluginApplication"))
    }
  }

  build {
    dependsOn(shadowJar)
  }

  test {
    useJUnitPlatform()
    enabled = project.hasProperty("runBuildSrcTests")
  }
}
