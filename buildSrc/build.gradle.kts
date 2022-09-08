plugins {
  java
  groovy
  `java-gradle-plugin`
  id("com.diffplug.spotless") version "5.11.0"
}

spotless {
  java {
    toggleOffOn()
    // set explicit target to workaround https://github.com/diffplug/spotless/issues/1163
    target("src/**/*.java")
    // ignore embedded test projects
    targetExclude("src/test/resources/**")
    googleJavaFormat()
  }
}

gradlePlugin {
  plugins {
    create("instrument-plugin") {
      id = "instrument"
      implementationClass = "InstrumentPlugin"
    }
    create("muzzle-plugin") {
      id = "muzzle"
      implementationClass = "MuzzlePlugin"
    }
    create("call-site-instrumentation-plugin") {
      id = "call-site-instrumentation"
      implementationClass = "CallSiteInstrumentationPlugin"
    }
  }
}

repositories {
  mavenLocal()
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  implementation(gradleApi())
  implementation(localGroovy())

  implementation("net.bytebuddy", "byte-buddy-gradle-plugin", "1.12.12")

  implementation("org.eclipse.aether", "aether-connector-basic", "1.1.0")
  implementation("org.eclipse.aether", "aether-transport-http", "1.1.0")
  implementation("org.apache.maven", "maven-aether-provider", "3.3.9")

  implementation("com.google.guava", "guava", "20.0")
  implementation("org.ow2.asm", "asm", "9.0")
  implementation("org.ow2.asm", "asm-tree", "9.0")

  implementation("org.freemarker", "freemarker", "2.3.30")

  testImplementation("org.spockframework", "spock-core", "2.0-groovy-3.0")
  testImplementation("org.codehaus.groovy", "groovy-all", "3.0.10")
  testImplementation("com.github.javaparser", "javaparser-symbol-solver-core", "3.24.4")
}

val copyCallSiteSources = tasks.register<Copy>("copyCallSiteSources") {
  val csiPackage = "datadog/trace/agent/tooling/csi"
  val source = layout.projectDirectory.file("../dd-java-agent/agent-tooling/src/main/java/$csiPackage")
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
tasks.getByPath("compileJava").dependsOn(copyCallSiteSources)

sourceSets {
  main {
    java {
      srcDirs("src/main/java", "$buildDir/generated/sources/csi")
    }
  }
}

tasks.test {
  useJUnitPlatform()
}
