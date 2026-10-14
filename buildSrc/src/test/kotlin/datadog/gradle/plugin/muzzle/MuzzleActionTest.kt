package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.muzzle.MuzzleAction.Companion.classLoaders
import datadog.trace.agent.tooling.muzzle.TestMuzzleVersionScanPlugin
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong

class MuzzleActionTest {
  @TempDir lateinit var directory: File
  private val project by lazy { ProjectBuilder.builder().withProjectDir(directory).build() }
  private val buildStamp = buildTimes.addAndGet(2)

  @Test
  fun `otel helpers link after a larger unrelated bootstrap classpath`() {
    val parameters = parameters()
    parameters.bootstrapClassPath.from(marker("installer"), marker("installer-dependencies"))
    execute(parameters)

    parameters.bootstrapClassPath.setFrom(classes(TestMuzzleVersionScanPlugin.OtelInstrument::class.java))
    parameters.instrumentationClassPath.from(classes(TestMuzzleVersionScanPlugin.OtelHelper::class.java))
    parameters.muzzleDirective.set("otel")
    execute(parameters)
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `both ordered classpaths participate in cache identity`(bootstrap: Boolean) {
    val first = marker("first")
    val second = marker("second")
    val third = marker("third")
    val empty = project.files()
    fun load(files: FileCollection) = classLoaders(
      buildStamp,
      if (bootstrap) files else empty,
      if (bootstrap) empty else files,
    )
    val original = load(project.files(first, second))

    for (changed in listOf(listOf(first), listOf(first, second, third), listOf(first, third), listOf(second, first))) {
      assertThat(load(project.files(changed)).second).describedAs("classpath %s", changed).isNotSameAs(original.second)
    }
    assertThat(load(project.files(first, second)).second).isSameAs(original.second)
    assertThat(load(project.files(first)).second.getResource("second.marker")).isNull()
    assertThat(load(project.files(second, first)).second.getResource("marker")!!.readText()).isEqualTo("second")
  }

  @Test
  fun `moving a file between tooling and bootstrap changes visibility`() {
    val shared = project.files(marker("shared"))
    val before = classLoaders(buildStamp, project.files(), shared)
    val after = classLoaders(buildStamp, shared, project.files())
    assertThat(before.first.getResource("marker")).isNull()
    assertThat(after.first.getResource("marker")!!.readText()).isEqualTo("shared")
  }

  @ParameterizedTest
  @ValueSource(longs = [-1, 1])
  fun `a changed build timestamp discards all cached loaders`(offset: Long) {
    val paths = listOf(project.files(marker("first")), project.files(marker("second")))
    val tooling = project.files()
    val before = paths.map { classLoaders(buildStamp, it, tooling) }
    val after = paths.map { classLoaders(buildStamp + offset, it, tooling) }
    before.zip(after).forEach { (old, fresh) ->
      assertThat(fresh.first).isNotSameAs(old.first)
      assertThat(fresh.second).isNotSameAs(old.second)
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `concurrent lookups return the correct loader pair`(distinct: Boolean) {
    val names = List(8) { if (distinct) it.toString() else "shared" }
    val paths = names.map { project.files(marker(it)) }
    val tooling = project.files()
    val start = CyclicBarrier(4)
    val executor = Executors.newFixedThreadPool(start.parties)
    try {
      val tasks = paths.map { path ->
        Callable {
          start.await(10, SECONDS)
          classLoaders(buildStamp, path, tooling)
        }
      }
      val results = executor.invokeAll(tasks, 10, SECONDS).map { it.get() }
      results.zip(names).forEach { (loaders, name) ->
        assertThat(loaders.second.parent).isSameAs(loaders.first)
        assertThat(loaders.second.getResource("marker")!!.readText()).isEqualTo(name)
      }
      if (!distinct) assertThat(results.map { it.second }).containsOnly(results.first().second)
    } finally {
      executor.shutdownNow()
      assertThat(executor.awaitTermination(10, SECONDS)).isTrue()
    }
  }

  @Test
  fun `an old lookup cannot populate the next build cache`() {
    val files = project.files(marker("shared"))
    val tooling = project.files()
    val creating = CountDownLatch(1)
    val resume = CountDownLatch(1)
    val paused = object : FileCollection by files {
      override fun iterator(): MutableIterator<File> {
        creating.countDown()
        check(resume.await(10, SECONDS))
        return files.iterator()
      }
    }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val old = executor.submit(Callable { classLoaders(buildStamp, paused, tooling) })
      assertThat(creating.await(5, SECONDS)).isTrue()
      val fresh = executor.submit(Callable { classLoaders(buildStamp + 1, files, tooling) }).get(5, SECONDS)
      resume.countDown()
      val previous = old.get(5, SECONDS)
      assertThat(fresh.first).isNotSameAs(previous.first)
      assertThat(fresh.second).isNotSameAs(previous.second)
      assertThat(classLoaders(buildStamp + 1, files, tooling)).isSameAs(fresh)
    } finally {
      resume.countDown()
      executor.shutdownNow()
      assertThat(executor.awaitTermination(10, SECONDS)).isTrue()
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `scanner receives assertion flags and optional directives`(assertPass: Boolean) {
    val parameters = parameters()
    parameters.assertPass.set(assertPass)
    if (assertPass) parameters.muzzleDirective.set("named")
    val arguments = execute(parameters)
    assertThat(arguments[2]).isEqualTo(assertPass)
    assertThat(arguments[3]).isEqualTo(if (assertPass) "named" else null)
  }

  @Test
  fun `application and instrumentation loaders are fresh and isolated`() {
    val parameters = parameters()
    parameters.instrumentationClassPath.from(marker("instrumentation"))
    parameters.testApplicationClassPath.from(marker("application"))
    val before = execute(parameters)
    val instrumentation = before[0] as ClassLoader
    val application = before[1] as ClassLoader
    assertThat(instrumentation.getResource("instrumentation.marker")).isNotNull()
    assertThat(application.getResource("application.marker")).isNotNull()
    assertThat(instrumentation.getResource("application.marker")).isNull()
    assertThat(application.getResource("instrumentation.marker")).isNull()
    assertThat(instrumentation.parent.parent).isSameAs(application.parent)
    parameters.instrumentationClassPath.setFrom(emptyList<File>())
    parameters.testApplicationClassPath.setFrom(emptyList<File>())
    val after = execute(parameters)
    assertThat(after[0]).isNotSameAs(instrumentation)
    assertThat(after[1]).isNotSameAs(application)
    assertThat((after[0] as ClassLoader).getResource("instrumentation.marker")).isNull()
    assertThat((after[1] as ClassLoader).getResource("application.marker")).isNull()
  }

  @ParameterizedTest
  @ValueSource(strings = ["fail", "error"])
  fun `scanner failures replace a stale success result`(directive: String) {
    val parameters = parameters()
    parameters.muzzleDirective.set(directive)
    val result = parameters.resultFile.get().asFile
    result.writeText("PASSING")
    assertThatThrownBy { execute(parameters) }.isInstanceOf(GradleException::class.java)
      .hasMessage("Muzzle validation failed").hasStackTraceContaining("scanner failure")
      .hasRootCauseInstanceOf(if (directive == "fail") IllegalStateException::class.java else AssertionError::class.java)
    assertThat(result.readText()).contains("scanner failure").doesNotContain("PASSING")
  }

  @Test
  fun `missing scanner fails before reporting success`() {
    val parameters = parameters()
    parameters.toolingClassPath.setFrom(emptyList<File>())
    assertThatThrownBy { execute(parameters) }.isInstanceOf(ClassNotFoundException::class.java)
    assertThat(parameters.resultFile.get().asFile).doesNotExist()
  }

  @Test
  fun `default parent exposes JDK classes and hides Gradle`() {
    (MuzzleAction.createClassLoader(project.files()) as URLClassLoader).use {
      assertThat(it.loadClass("java.sql.Driver")).isSameAs(java.sql.Driver::class.java)
      assertThatThrownBy { it.loadClass("org.gradle.api.Project") }.isInstanceOf(ClassNotFoundException::class.java)
    }
  }

  private fun parameters() = project.objects.newInstance(MuzzleWorkParameters::class.java).apply {
    buildStartedTime.set(buildStamp)
    assertPass.set(true)
    toolingClassPath.from(classes(TestMuzzleVersionScanPlugin::class.java))
    resultFile.set(File(directory, "result.txt"))
  }

  private fun execute(parameters: MuzzleWorkParameters): Array<*> {
    object : MuzzleAction() {
      override val scanPluginClassName = TestMuzzleVersionScanPlugin::class.java.name
      override fun getParameters() = parameters
    }.execute()
    assertThat(parameters.resultFile.get().asFile).hasContent("PASSING")
    val tooling = classLoaders(parameters.buildStartedTime.get(), parameters.bootstrapClassPath, parameters.toolingClassPath).second
    return tooling.loadClass(TestMuzzleVersionScanPlugin::class.java.name).getField("arguments").get(null) as Array<*>
  }

  private fun marker(name: String) = File(directory, name).apply {
    mkdirs()
    File(this, "marker").writeText(name)
    File(this, "$name.marker").writeText(name)
  }

  private fun classes(type: Class<*>) = File(directory, type.simpleName).apply {
    val resource = type.name.replace('.', '/') + ".class"
    val target = File(this, resource)
    target.parentFile.mkdirs()
    type.getResourceAsStream("/$resource")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
  }

  companion object {
    private val buildTimes = AtomicLong()
  }
}
