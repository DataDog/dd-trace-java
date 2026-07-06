import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

/**
 * Attaches the modifiable-config Java agent to every Test task so that
 * `datadog.trace.api.Config` and `datadog.trace.api.InstrumenterConfig` have
 * their `INSTANCE` field rewritten as public/volatile/non-final at load time.
 *
 * This is the load-time counterpart to
 * `datadog.trace.junit.utils.config.WithConfigExtension`, which uses ByteBuddy
 * to retransform the same classes at runtime. Retransformation does not always
 * succeed when the class has been touched before the JUnit lifecycle starts
 * (e.g. through `CoreTracer`); the agent guarantees the rewrite by intercepting
 * the class before it is defined.
 *
 * The agent jar is built by the `:modifiable-config-agent` buildSrc subproject
 * and is produced eagerly because the parent `buildSrc` compilation depends on
 * it.
 */
val agentJar = rootProject.layout.projectDirectory
  .file("buildSrc/modifiable-config-agent/build/libs/modifiable-config-agent.jar")
  .asFile

tasks.withType<Test>().configureEach {
  inputs.file(agentJar).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.NONE)
  // Attach lazily so we can skip when the Test JVM already has another -javaagent.
  // doFirst prepends, so this runs after any -javaagent registered later via doFirst.
  doFirst {
    val foreignAgent = allJvmArgs.firstOrNull {
      it.startsWith("-javaagent:") && !it.contains("modifiable-config-agent")
    }
    if (foreignAgent != null) {
      logger.info(
        "[modifiable-config] skipping attach for {} — another -javaagent already present: {}",
        path,
        foreignAgent,
      )
    } else {
      jvmArgs("-javaagent:${agentJar.absolutePath}")
    }
  }
}
