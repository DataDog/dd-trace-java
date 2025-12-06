import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  java
  application
  id("com.github.johnrengelman.shadow") version "7.0.0"
}

if (hasProperty("appBuildDir")) {
  buildDir = file(property("appBuildDir"))
}

group = "datadog"
version = "1.0.0-SNAPSHOT"

repositories {
  mavenCentral()
}

val vertxVersion = "3.9.8"
val junitJupiterVersion = "5.7.0"

val launcherClassName = "datadog.vertx_3_9.MainVerticle"

application {
  mainClass.set(launcherClassName)
}

dependencies {
  implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
  implementation("io.vertx:vertx-rx-java2")
  implementation("io.vertx:vertx-web")
  implementation("io.vertx:vertx-redis-client")
  implementation("org.jboss.resteasy:resteasy-vertx:4.5.8.Final")
  testImplementation("io.vertx:vertx-junit5")
  testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<ShadowJar> {
  archiveClassifier.set("fat")
  mergeServiceFiles()
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events = setOf(PASSED, SKIPPED, FAILED)
  }
}
