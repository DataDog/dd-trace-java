plugins {
  groovy
  `java-gradle-plugin`
  id("com.diffplug.spotless") version "5.11.0"
}

gradlePlugin {
  plugins {
    create("muzzle-plugin") {
      id = "muzzle"
      implementationClass = "MuzzlePlugin"
    }
    create("minimize-plugin") {
      id = "minimize"
      implementationClass = "MinimizePlugin"
    }
  }
}

repositories {
  mavenLocal()
  jcenter()
  mavenCentral()
}

dependencies {
  implementation(gradleApi())
  implementation(localGroovy())

  implementation("org.eclipse.aether", "aether-connector-basic", "1.1.0")
  implementation("org.eclipse.aether", "aether-transport-http", "1.1.0")
  implementation("org.apache.maven", "maven-aether-provider", "3.3.9")

  implementation("com.google.guava", "guava", "20.0")
  implementation("org.ow2.asm", "asm", "9.0")
  implementation("org.ow2.asm", "asm-tree", "9.0")

  implementation("org.vafer", "jdependency", "2.6.0")

  testImplementation("org.spockframework", "spock-core", "2.0-M4-groovy-2.5")
  testImplementation("org.codehaus.groovy", "groovy-all", "2.5.13")
}
