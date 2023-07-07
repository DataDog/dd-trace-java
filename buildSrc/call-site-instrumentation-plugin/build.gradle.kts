import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  java
  groovy
  id("com.diffplug.spotless") version "6.11.0"
  id("com.github.johnrengelman.shadow") version "7.1.2"
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

repositories {
  mavenLocal()
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  compileOnly("com.google.code.findbugs", "jsr305", "3.0.2")

  implementation("org.freemarker", "freemarker", "2.3.30")
  implementation("org.ow2.asm", "asm", "9.0")
  implementation("org.ow2.asm", "asm-tree", "9.0")
  implementation("com.github.javaparser", "javaparser-symbol-solver-core", "3.24.4")

  testImplementation("net.bytebuddy", "byte-buddy", "1.11.10")
  testImplementation("org.spockframework", "spock-core", "2.0-groovy-3.0")
  testImplementation("org.objenesis", "objenesis", "3.0.1")
  testImplementation("org.codehaus.groovy", "groovy-all", "3.0.17")
  testImplementation("javax.servlet", "javax.servlet-api", "3.0.1")
  testImplementation("com.github.spotbugs", "spotbugs-annotations", "4.2.0")
}

sourceSets {
  test {
    java {
      srcDirs("src/test/java", "$buildDir/generated/sources/csi")
    }
  }
}

val copyCallSiteSources = tasks.register<Copy>("copyCallSiteSources") {
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

tasks {
  withType<AbstractCompile>() {
    dependsOn(copyCallSiteSources)
  }
}

tasks {
  named<ShadowJar>("shadowJar") {
    archiveBaseName.set("call-site-instrumentation-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
      attributes(mapOf("Main-Class" to "datadog.trace.plugin.csi.PluginApplication"))
    }
  }
}

tasks.build {
  dependsOn(tasks.shadowJar)
}

tasks.test {
  useJUnitPlatform()
  enabled = project.hasProperty("runBuildSrcTests")
}
