plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
}

// The Endive host runtime and the AOT machine classes are class-file v55, so the
// engine tests cannot run on Java 8 (the rest of the module still emits Java 8
// bytecode for the poller itself, matching internal-api-9's split)
testJvmConstraints {
  minJavaVersion = JavaVersion.VERSION_11
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
}

// ---------------------------------------------------------------------------
// WASM remote-config engine artifact
//
// Default: resolve the published AOT artifact (machine classes, stripped module,
// host library) from the maven-dist git submodule (the distribution repository's
// gh-pages branch), so the build needs no wasm toolchain and no maven egress.
// The artifact is built and published from the libdatadog crate's Java host
// (bindings/wasm/remote-config-poc); the GitHub Pages URL is the fallback for
// clones without submodules (see gradle/repositories.gradle).
//
// -PwasmFromSource=true builds from the submodule instead, replicating the
// Java-relevant part of the crate's scripts/build.mjs:
//
//   submodulesUpdate  git submodule update --init libdatadog
//   wasmGenerate      node scripts/generate.mjs  (abi.json -> src/generated.rs)
//   wasmCargoBuild    cargo build --target wasm32-wasip1 --release --locked
//   wasmOptJava       wasm-opt (Binaryen v133) -> remote-config-java.wasm
//   wasmAotCompile    Endive build-time compiler -> RemoteConfigMachine* classes
//
// Source mode carries the -Oz/method-splitting experiment (Endive main-branch
// 999-SNAPSHOT), so its machine classes are not bit-identical to the published
// -O1/Endive-1.2.0 artifact; the published artifact is the reproducibility
// reference. See the module README for the measured tradeoffs.
// ---------------------------------------------------------------------------

val wasmFromSource: Boolean =
  providers.gradleProperty("wasmFromSource").map(String::toBoolean).getOrElse(false)

/** Coordinates of the published AOT artifact (provenance: remote-config-aot.json in the jar). */
val publishedAot = "io.github.bantonsson:remote-config-wasm-aot:0.1.0+b4106c4"

repositories {
  if (wasmFromSource) {
    // Endive 999-SNAPSHOT (method splitting, endive#270) is unreleased; it comes from
    // a local `./mvnw install` build of the Endive repository. The published artifact
    // resolves from the shared repo list (gradle/repositories.gradle), which consumers
    // like the agent shadowJar see as well.
    mavenLocal()
  }
}

// Coverage: com.datadoghq.remoteconfig.poc is third-party code with AOT class
// names that vary per wasm build, so exclude the whole package. The poller runs
// at lower minimums than the repo default: the guest drives the poll loop, and
// the remaining paths (error logging, listener corner cases) need long failure
// sequences to reach.
extra["minimumBranchCoverage"] = 0.6
extra["minimumInstructionCoverage"] = 0.8
extra["excludedClassesCoverage"] =
  listOf(
    // vendored libdatadog host library + generated AOT reactor classes (wasm function bodies)
    "com.datadoghq.remoteconfig.poc.*",
  )

extra["excludedClassesInstructionCoverage"] = listOf(
  "datadog.remoteconfig.OkHttpTransport",
  "datadog.remoteconfig.OkHttpTransport.1",
)

extra["excludedClassesBranchCoverage"] = listOf(
  // GET-without-body branch only triggers if the guest ever sends a bodyless request
  "datadog.remoteconfig.OkHttpTransport",
  "datadog.remoteconfig.OkHttpTransport.1",
)

// ---------------------------------------------------------------------------
// libdatadog WASM remote-config reactor, built from the git submodule
// (source mode only; everything below is inert when -PwasmFromSource is unset)
// ---------------------------------------------------------------------------

/** The libdatadog checkout inside this module. */
val libdatadog = layout.projectDirectory.dir("libdatadog")

/** Runs a command and captures its stdout, failing the build on a non-zero exit. */
fun String.capture(): String {
  val parts = trim().split(Regex("\\s+")).toTypedArray()
  val process = ProcessBuilder(*parts).redirectErrorStream(true).start()
  val output = process.inputStream.bufferedReader().readText().trim()
  if (process.waitFor() != 0) throw GradleException("`$this` failed: $output")
  return output
}

/** The remote-config WASM crate inside libdatadog. */
val rcCrate = libdatadog.dir("bindings/wasm/remote-config-poc")

/** Rust toolchain channel, from the crate's rust-toolchain.toml. */
val rustToolchain by lazy {
  val toolchainFile = rcCrate.file("rust-toolchain.toml").asFile
  Regex("""^channel = "([^"]+)"""", RegexOption.MULTILINE)
    .find(toolchainFile.readText())
    ?.groupValues?.get(1)
    ?: throw GradleException("Missing channel in $toolchainFile")
}

/**
 * LLVM bin dir for CC_wasm32_wasip1/AR_wasm32_wasip1, mirroring build.mjs: the
 * LLVM_BIN environment variable when set, otherwise the Homebrew path on macOS.
 */
val llvmBin: String? =
  providers.environmentVariable("LLVM_BIN").orNull
    ?: if (System.getProperty("os.name").lowercase().contains("mac")) {
      if (System.getProperty("os.arch") == "aarch64") "/opt/homebrew/opt/llvm/bin" else "/usr/local/opt/llvm/bin"
    } else {
      null
    }

/** The wasm-opt binary; WASM_OPT overrides the location like in build.mjs. */
val wasmOptBinary = providers.environmentVariable("WASM_OPT").orElse("wasm-opt").get()

/** Build directory layout for the wasm artifacts. */
val wasmBuildDir = layout.buildDirectory.dir("wasm")
val aotSources = wasmBuildDir.map { it.dir("aot/sources") }
val aotClasses = wasmBuildDir.map { it.dir("aot/classes") }
val aotWasmDir = wasmBuildDir.map { it.dir("aot/wasm") }
val javaWasm = wasmBuildDir.map { it.file("remote-config-java.wasm") }
val cargoWasm = rcCrate.file("target/wasm32-wasip1/release/libdd_remote_config_wasm_poc.wasm")

// The AOT input is the -Oz artifact from wasmOptJava; -Pwasm=<path> overrides
// it with a pre-built reactor for local experiments (then the wasm chain is
// skipped entirely).
val wasmOverride = providers.gradleProperty("wasm")
val aotWasmInput: Provider<File> =
  wasmOverride.map { projectDir.resolve(it) }.orElse(javaWasm.map { it.asFile })

tasks.register("submodulesUpdate", Exec::class) {
  group = "Build Setup"
  description = "Initializes and updates the libdatadog git submodule"
  commandLine("git", "submodule", "update", "--init", "libdatadog")
  outputs.dir(libdatadog)
  // A missing checkout must always re-run; a populated one is tracked by gitlink
  if (libdatadog.asFile.list().isNullOrEmpty()) outputs.upToDateWhen { false }
}

tasks.register("wasmGenerate", Exec::class) {
  group = "Build"
  description = "Generates the Rust ABI glue from the crate's abi.json"
  dependsOn("submodulesUpdate")
  inputs.file(rcCrate.file("abi.json"))
  inputs.file(rcCrate.file("scripts/generate.mjs"))
  outputs.file(rcCrate.file("src/generated.rs"))
  workingDir(rcCrate)
  commandLine("node", "scripts/generate.mjs")
}

tasks.register("wasmCargoBuild", Exec::class) {
  group = "Build"
  description = "Builds the libdatadog remote-config reactor crate for wasm32-wasip1"
  dependsOn("submodulesUpdate", "wasmGenerate")
  // Inputs: every Rust/C source and manifest under libdatadog, because the
  // crate pulls sibling libdd-* crates by path.
  inputs.files(
    fileTree(libdatadog) {
      include("**/*.rs", "**/*.toml", "**/*.c", "**/*.h")
      exclude("**/target/**", "**/.git/**", "**/artifacts/**")
    }
  )
  outputs.dir(rcCrate.dir("target"))
  // cargo keeps its own incremental state; never skip on Gradle's behalf
  outputs.upToDateWhen { false }
  workingDir(rcCrate)
  environment("RUSTC", "rustup which --toolchain $rustToolchain rustc".capture())
  // build.mjs defaults the wasm C toolchain to plain clang/llvm-ar when LLVM_BIN is
  // unset; without these the ring dependency links with missing ring_core_* symbols
  environment("CC_wasm32_wasip1", llvmBin?.let { "$it/clang" } ?: "clang")
  environment("AR_wasm32_wasip1", llvmBin?.let { "$it/llvm-ar" } ?: "llvm-ar")
  commandLine("rustup", "run", rustToolchain, "cargo", "build", "--target", "wasm32-wasip1", "--release", "--locked")
}

// The published artifact is built from the -O1 reactor with Endive 1.2.0.
// Source mode pairs -Oz --converge with Endive
// method splitting (endive#270): oversized wasm functions are split across JVM
// methods instead of falling back to the interpreter, keeping every function
// AOT-compiled at the smallest artifact size.
tasks.register("wasmOptJava", Exec::class) {
  group = "Build"
  description = "Optimizes the wasm reactor for AOT compilation (wasm-opt -Oz --converge)"
  dependsOn("wasmCargoBuild")
  inputs.file(cargoWasm)
  outputs.file(javaWasm)
  doFirst {
    // Binaryen v133 exactly: its output shape feeds the AOT compiler
    val version = "$wasmOptBinary --version".capture()
    if (!Regex("""^wasm-opt version 133\b""").containsMatchIn(version)) {
      throw GradleException("Binaryen wasm-opt version 133 is required, found: $version")
    }
  }
  commandLine(
    wasmOptBinary,
    cargoWasm.asFile,
    "-Oz",
    "--converge",
    "--enable-mutable-globals",
    "--enable-nontrapping-float-to-int",
    "--enable-bulk-memory",
    "--enable-sign-ext",
    "--enable-reference-types",
    "--enable-multivalue",
    "--disable-gc",
    "-o",
    javaWasm.get(),
  )
}

// Classpath for the Endive build-time compiler CLI (wasm -> JVM classes).
val wasmCompilerCli: Configuration = configurations.create("wasmCompilerCli")

// Ahead-of-time compile the reactor with the Endive build-time compiler CLI:
// every wasm function body becomes JVM bytecode, the stripped module structure
// is kept in a RemoteConfig.meta wasm, and a RemoteConfig wrapper source is
// generated for the main source set. Mirrors hosts/java/build.gradle.kts.
tasks.register("wasmAotCompile", JavaExec::class) {
  group = "Build"
  description = "AOT-compiles the wasm reactor into JVM classes with the Endive compiler"
  if (!wasmOverride.isPresent) dependsOn("wasmOptJava")
  classpath = wasmCompilerCli
  mainClass.set("run.endive.experimental.compiler.cli.Cli")
  inputs.file(aotWasmInput)
  outputs.dirs(aotSources, aotClasses, aotWasmDir)
  argumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        aotWasmInput.get().toString(),
        "--prefix", "com.datadoghq.remoteconfig.poc.RemoteConfig",
        "--source-dir", aotSources.get().toString(),
        "--class-dir", aotClasses.get().toString(),
        "--wasm-dir", aotWasmDir.get().toString(),
        "--method-splitting",
        "--interpreter-fallback", "FAIL",
      )
    }
  )
}

sourceSets {
  main {
    if (wasmFromSource) {
      // The libdatadog Java host library compiles straight from the submodule;
      // the CLI demo classes (Main, JavaNetHttpTransport) stay out.
      java.srcDir(rcCrate.dir("hosts/java/src/main/java"))
      java.exclude("com/datadoghq/remoteconfig/poc/Main.java")
      java.exclude("com/datadoghq/remoteconfig/poc/JavaNetHttpTransport.java")
      // The generated RemoteConfig wrapper source from wasmAotCompile
      java.srcDir(aotSources)
      // The machine classes and the stripped .meta wasm load from the classpath
      resources.srcDir(aotClasses)
      resources.srcDir(aotWasmDir)
    }
  }
}

tasks.named<JavaCompile>("compileJava") {
  if (wasmFromSource) {
    dependsOn("wasmAotCompile")
    // The generated wrapper references the machine classes
    classpath += files(aotClasses)
  }
}

tasks.named("processResources") {
  if (wasmFromSource) {
    dependsOn("wasmAotCompile")
  }
}

// javadoc and the sources jar read the main source set; in source mode that
// includes the submodule host library and the generated AOT wrapper source
for (taskName in listOf("javadoc", "sourcesJar")) {
  tasks.named(taskName) {
    if (wasmFromSource) {
      dependsOn("submodulesUpdate", "wasmAotCompile")
    }
  }
}

// The repo pins javadoc to JDK 8, but the Endive host runtime the host library
// documents against is Java 11 bytecode (class file v55)
tasks.named<Javadoc>("javadoc") {
  javadocTool = javaToolchains.javadocToolFor {
    languageVersion.set(JavaLanguageVersion.of(11))
  }
  // The repo-wide javadoc config links the JDK 8 API docs, and the forked javadoc
  // process has no proxy configuration; skip external links for this module
  (options as org.gradle.external.javadoc.StandardJavadocDocletOptions).links = emptyList()
}

dependencies {
  if (wasmFromSource) {
    wasmCompilerCli("run.endive:build-time-compiler-cli-experimental:999-SNAPSHOT")
    implementation("run.endive:runtime:999-SNAPSHOT")
    implementation("run.endive:wasi:999-SNAPSHOT")
    implementation("org.json:json:20250517")
  } else {
    // machine classes, RemoteConfig.meta, host library; POM adds org.json and run.endive 1.2.0
    implementation(publishedAot)
  }

  api(project(":remote-config:remote-config-api"))

  // ParsedConfigKey and PollingHinterNoop for change dispatch
  implementation(project(":remote-config:remote-config-core"))
  implementation(project(":internal-api"))
  implementation(project(":utils:logging-utils"))

  implementation(libs.slf4j)
  implementation(libs.datadog.okhttp)

  testImplementation(project(":utils:test-utils"))
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.assertj.core)
}
