import gradle.kotlin.dsl.accessors._5c4f8bbe3a62f1bcdc0caabeff2db4ed.compileTestGroovy

// Enable testing kotlin code in groovy spock tests.
plugins {
  id("org.jetbrains.kotlin.jvm")
  id("datadog.spotless-kotlin")
}

// NOTE: Kotlin sourcesSet does not seem accessible from Kotlin DSL
// REFERENCE: https://discuss.gradle.org/t/how-to-access-kotlin-source-set-directory-using-the-kotlin-dsl/36116
//val SourceSet.kotlin: SourceDirectorySet
//  get() = project.extensions.getByType<KotlinJvmProjectExtension>().sourceSets.getByName(name).kotlin
//
//tasks.compileTestGroovy {
//  classpath += files(sourceSets.test.get().kotlin.classesDirectory)
//}

tasks.compileTestGroovy {
  classpath += files(layout.buildDirectory.dir("classes/kotlin/test"))
}

//// Having Groovy, Kotlin and Java in the same project is a bit problematic
//// this removes Kotlin from main source set to avoid compilation issues
//sourceSets.main.kotlin.srcDir = []
//sourceSets {
//  main {
//    kotlin.setSrcDirs(emptyList<File>())
//    java {
//      srcDirs.clear()
//      srcDirs.add(File("src/main/java"))
//    }
//  }
//}

//// this creates Kotlin dirs to make JavaCompile tasks work
//var createKotlinDirs = tasks.register("createKotlinDirs") {
//  var dirsToCreate = ["classes/kotlin/main"]
//  doFirst {
//    dirsToCreate.forEach {
//      it -> File(project.buildDir, it).mkdirs()
//    }
//  }
//
//  outputs.dirs(
//    dirsToCreate.collect {
//      project.layout.buildDirectory.dir(it)
//    }
//  )
//}
//
//tasks.withType(JavaCompile).configureEach {
//  inputs.files(createKotlinDirs)
//}
