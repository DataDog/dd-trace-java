package datadog.telemetry.dependency

import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.utility.JavaModule
import net.bytebuddy.utility.nullability.MaybeNull
import org.apache.tools.ant.taskdefs.Classloader
import org.springframework.boot.loader.net.protocol.Handlers
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Path
import java.nio.file.Paths
import java.security.ProtectionDomain
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.jar.JarFile

import static net.bytebuddy.matcher.ElementMatchers.named
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments

class DependencyResolverSpring3Specification extends Specification {

  @Shared
  private ResettableClassFileTransformer transformer

  @Shared
  private ExecutorService executors

  @Shared
  private Path jarPath

  @SuppressWarnings('GroovyAccessibility')
  void setupSpec() {
    // separate thread to read telemetry dependencies
    executors = Executors.newSingleThreadExecutor()

    // transform spring boot jar file
    transformer = transformJarFile()

    // register spring jar and nested handlers
    Handlers.register()

    // link to the jar we are going to analyze
    final root = Paths.get(Classloader.classLoader.getResource('').toURI())
    final path = root.resolve('../../../resources/test/datadog/telemetry/dependencies/spring-boot-app.jar')
    jarPath = Paths.get(path.toFile().canonicalPath)
  }

  void cleanupSpec() {
    executors.shutdown()
    transformer.reset(ByteBuddyAgent.instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)

    CloseCallbackAdvice.BEFORE = null
    CloseCallbackAdvice.AFTER = null
  }


  @SuppressWarnings('GroovyAccessibility')
  void 'resolving nested dependency does not interfere with application reading the same jar'() {
    given:
    final target = "BOOT-INF/lib/opentracing-util-0.33.0.jar"
    final nestedJar = new URL("jar:nested:${jarPath.toString()}/!${target}!/")
    final nestedClass = new URL("${nestedJar}io/opentracing/util/GlobalTracer.class")

    // callback that ensure we do not close the nested jar prematurely
    final close = new CountDownLatch(1)
    CloseCallbackAdvice.BEFORE = (JarFile jarFile) -> {
      if (jarFile.name.endsWith(target)) {
        close.await()
        CloseCallbackAdvice.BEFORE = null
      }
    }

    // callback to wait for the nested jar to be fully closed
    final read = new CountDownLatch(1)
    CloseCallbackAdvice.AFTER = (JarFile jarFile) -> {
      if (jarFile.name.endsWith(target)) {
        read.countDown()
        CloseCallbackAdvice.AFTER = null
      }
    }

    when:
    // fetch nested resources from the jar and start reading
    final inputStream = nestedClass.openConnection().inputStream
    inputStream.read()

    // trigger telemetry collection in a separate thread
    final future = executors.submit(() -> {
      return DependencyResolver.resolve(new DependencyService().convertToURI(nestedJar))
    } as Callable<List<Dependency>>)

    // trigger the close of the nested jar
    close.countDown()

    // wait until the nested jar has been fully closed
    read.await()

    // continue reading the nested resource
    inputStream.read()
    inputStream.close()

    then:
    final dep = future.get().first()
    dep.name == 'io.opentracing:opentracing-util'
    dep.version == '0.33.0'
    dep.source == 'opentracing-util-0.33.0.jar'
  }

  /**
   * Transform {@link org.springframework.boot.loader.net.protocol.jar.UrlNestedJarFile} so we can manipulate the state
   * when the actual file is closed
   */
  private static ResettableClassFileTransformer transformJarFile() {
    return new AgentBuilder.Default()
      .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
      .with(AgentBuilder.InjectionStrategy.UsingUnsafe.INSTANCE)
      .type(named("org.springframework.boot.loader.net.protocol.jar.UrlNestedJarFile"))
      .transform(new AgentBuilder.Transformer() {
        @Override
        DynamicType.Builder<?> transform(final DynamicType.Builder<?> builder,
          final TypeDescription typeDescription,
          final @MaybeNull ClassLoader classLoader,
          final @MaybeNull JavaModule javaModule,
          final ProtectionDomain protectionDomain) {
          return builder.visit(Advice.to(CloseCallbackAdvice).on(named("close").and(takesNoArguments())))
        }
      })
      .installOnByteBuddyAgent()
  }

}
