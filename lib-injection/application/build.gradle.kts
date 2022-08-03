import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    id("org.springframework.boot") version "2.7.2"
    id("io.spring.dependency-management") version "1.0.11.RELEASE"
    id("java")
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_11

dependencies {
    implementation("org.springframework.fu:spring-fu-jafu:0.5.1")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.spring.io/milestone")
    maven("https://repo.spring.io/snapshot")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val dockerImageRepo: String? by project
val resolvedDockerImageRepo: String = dockerImageRepo ?: "docker.io/" + System.getenv("DOCKER_USERNAME") + "/dd-java-agent-init-test-app"
val dockerImageTag: String by project
tasks.named<BootBuildImage>("bootBuildImage") {
    imageName = "${resolvedDockerImageRepo}:${dockerImageTag}"
}
