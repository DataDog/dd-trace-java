plugins {
  java
  groovy
  id("com.diffplug.spotless") version "6.13.0"
  id("com.gradleup.shadow") version "8.3.6"
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
    // This is the last Google Java Format version that supports Java 8
    googleJavaFormat("1.7")
  }
}

apply {
  from("$rootDir/../gradle/repositories.gradle")
}

dependencies {
  compileOnly("com.google.code.findbugs", "jsr305", "3.0.2")

  implementation("org.freemarker", "freemarker", "2.3.30")
  implementation(libs.asm)
  implementation(libs.asm.tree)
  implementation(libs.javaparser.solver)

  testImplementation(libs.bytebuddy)
  testImplementation(libs.groovy4)
  testImplementation(libs.spock.core.groovy4)
  testImplementation("javax.servlet", "javax.servlet-api", "3.0.1")
  testImplementation("com.github.spotbugs", "spotbugs-annotations", "4.2.0")
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
    mergeServiceFiles()
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
