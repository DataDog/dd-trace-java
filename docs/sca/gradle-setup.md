# SCA Gradle Setup — Embedding pom.properties

How to embed `META-INF/maven/.../pom.properties` in a JAR so that `DependencyResolver` reports it
as a Maven artifact in SCA telemetry.

## How `DependencyResolver` identifies a Maven artifact

`DependencyResolver.resolve(URI)` scans inside each JAR for
`META-INF/maven/{groupId}/{artifactId}/pom.properties`. When found, it builds a `Dependency` with
`name = "{groupId}:{artifactId}"` and the version from the file. The `hash` field is `null` in
this case -- SHA-1 is only computed as a fallback when neither manifest nor pom.properties is
present.

## Canonical `WriteProperties` pattern

```groovy
def pomPropertiesDir = project.layout.buildDirectory.dir("generated/maven-metadata")
def pomPropertiesFileTree = fileTree(pomPropertiesDir)

tasks.named("processResources") { dependsOn(pomPropertiesFileTree) }
tasks.named("sourcesJar")       { dependsOn(pomPropertiesFileTree) }  // required -- see below

sourceSets {
    main.resources.srcDirs(includedAgentDir, pomPropertiesDir)  // srcDirs plural, not srcDir
}

def generatePomProperties = tasks.register('generatePomProperties', WriteProperties) {
    destinationFile = pomPropertiesDir.map {
        it.file("META-INF/maven/com.datadoghq/dd-java-agent/pom.properties")
    }
    property("groupId",    "com.datadoghq")
    property("artifactId", "dd-java-agent")
    property("version",    project.providers.provider { project.version.toString() })
}
pomPropertiesFileTree.builtBy(generatePomProperties)
```

## Invariants

### `sourcesJar` also needs `dependsOn`

`sourcesJar` uses `main.resources` as its source set. Without `dependsOn(pomPropertiesFileTree)`,
running `sourcesJar` alone fails with "srcDir does not exist" because the generated directory
has not been created yet.

### `srcDirs` (plural) when more than one generated directory exists

`main.resources.srcDir(a)` replaces the existing srcDir. When adding a second generated directory,
switch to `srcDirs(a, b)`.

### Use `providers.provider { }` for the version value

`project.version` evaluated directly in the task configuration block may not be resolved yet if
the versioning plugin runs later. Wrap it in `project.providers.provider { project.version.toString() }`
to defer evaluation to task execution time.

### `fileTree.builtBy(task)` placement

`pomPropertiesFileTree.builtBy(generatePomProperties)` must come after the `generatePomProperties`
variable is assigned. Gradle evaluates build scripts top-to-bottom in the configuration phase.

### Do not use `dependsOn(generatePomProperties)` directly

The repo uses `dependsOn(fileTree)` + `fileTree.builtBy(task)`. Keep this consistent.

## Testing `DependencyResolver`

```groovy
void 'jar with pom.properties resolves as Maven artifact'() {
    given:
    File file = new File(testDir, 'artifact.jar')
    new ZipOutputStream(new FileOutputStream(file)).with {
        putNextEntry(new ZipEntry('META-INF/maven/com.example/artifact/pom.properties'))
        write('groupId=com.example\nartifactId=artifact\nversion=1.2.3\n'.getBytes('UTF-8'))
        closeEntry()
        close()
    }

    when:
    List<Dependency> deps = DependencyResolver.resolve(file.toURI())

    then:
    deps[0].name    == 'com.example:artifact'
    deps[0].version == '1.2.3'
    deps[0].hash    == null  // hash is null when pom.properties is present
}
```

The `hash == null` assertion is a required invariant: SHA-1 is not computed when
`pom.properties` resolves the artifact.

## Smoke test verification

`AbstractServerSmokeTest.missingDependencyNames` verifies end-to-end that SCA telemetry reports
a set of known dependencies. To add a new artifact to the expected set, add it to that `Set`.
