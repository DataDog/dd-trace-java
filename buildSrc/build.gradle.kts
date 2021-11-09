plugins {
  groovy
  `java-gradle-plugin`
  id("com.diffplug.spotless") version "5.11.0"
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
  }
}

repositories {
  mavenLocal()
  mavenCentral()
}

dependencies {
  implementation(gradleApi())
  implementation(localGroovy())

  implementation("net.bytebuddy", "byte-buddy-gradle-plugin", "1.11.18")

  implementation("org.eclipse.aether", "aether-connector-basic", "1.1.0")
  implementation("org.eclipse.aether", "aether-transport-http", "1.1.0")
  implementation("org.apache.maven", "maven-aether-provider", "3.3.9")

  implementation("com.google.guava", "guava", "20.0")
  implementation("org.ow2.asm", "asm", "9.0")
  implementation("org.ow2.asm", "asm-tree", "9.0")

  testImplementation("org.spockframework", "spock-core", "2.0-M4-groovy-2.5")
  testImplementation("org.codehaus.groovy", "groovy-all", "2.5.13")
}

tasks.test {
  useJUnitPlatform()
}
