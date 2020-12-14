package datadog.trace.bootstrap

import datadog.trace.test.util.DDSpecification
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Phaser
import java.util.concurrent.TimeUnit

import static datadog.trace.api.Platform.isJavaVersionAtLeast
import static org.junit.Assume.assumeTrue

class DatadogClassLoaderTest extends DDSpecification {
  @Shared
  URL testJarLocation = new File("src/test/resources/classloader-test-jar/testjar-jdk8").toURI().toURL()

  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  def "DD classloader does not lock classloading around instance"() {
    setup:
    def className1 = 'some/class/Name1'
    def className2 = 'some/class/Name2'
    final URL loc = getClass().getProtectionDomain().getCodeSource().getLocation()
    final DatadogClassLoader ddLoader = new DatadogClassLoader(loc,
      null,
      new DatadogClassLoader.BootstrapClassLoaderProxy(),
      null)
    final Phaser threadHoldLockPhase = new Phaser(2)
    final Phaser acquireLockFromMainThreadPhase = new Phaser(2)

    when:
    final Thread thread1 = new Thread() {
      @Override
      void run() {
        synchronized (ddLoader.getClassLoadingLock(className1)) {
          threadHoldLockPhase.arrive()
          acquireLockFromMainThreadPhase.arriveAndAwaitAdvance()
        }
      }
    }
    thread1.start()

    final Thread thread2 = new Thread() {
      @Override
      void run() {
        threadHoldLockPhase.arriveAndAwaitAdvance()
        synchronized (ddLoader.getClassLoadingLock(className2)) {
          acquireLockFromMainThreadPhase.arrive()
        }
      }
    }
    thread2.start()
    thread1.join()
    thread2.join()
    boolean applicationDidNotDeadlock = true

    then:
    applicationDidNotDeadlock
  }


  def "test delegate class load to parent"() {
    given:
    assumeTrue(isJavaVersionAtLeast(8))
    DatadogClassLoader.BootstrapClassLoaderProxy bootstrapProxy =
      new DatadogClassLoader.BootstrapClassLoaderProxy()
    DatadogClassLoader parent = new DatadogClassLoader(testJarLocation, "parent", bootstrapProxy, null)
    DatadogClassLoader.DelegateClassLoader child = new DatadogClassLoader.DelegateClassLoader(testJarLocation,
      "child", bootstrapProxy, null, parent)

    when:
    Class<?> c = child.loadClass("a.b.c.C")

    then:
    c.getClassLoader() == parent
  }


  def "test delegate class load to bootstrap"() {
    given:
    assumeTrue(isJavaVersionAtLeast(8))
    DatadogClassLoader.BootstrapClassLoaderProxy bootstrapProxy =
      new DatadogClassLoader.BootstrapClassLoaderProxy()
    DatadogClassLoader parent = new DatadogClassLoader(testJarLocation, "parent", bootstrapProxy, null)
    DatadogClassLoader.DelegateClassLoader child = new DatadogClassLoader.DelegateClassLoader(testJarLocation,
      "child", bootstrapProxy, null, parent)

    when:
    Class<?> v = child.loadClass("java.lang.Void")

    then:
    v.getClassLoader() == null
  }

  def "test class load managed by child"() {
    given:
    assumeTrue(isJavaVersionAtLeast(8))
    DatadogClassLoader.BootstrapClassLoaderProxy bootstrapProxy =
      new DatadogClassLoader.BootstrapClassLoaderProxy()
    DatadogClassLoader parent = new DatadogClassLoader(testJarLocation, "parent", bootstrapProxy, null)
    DatadogClassLoader.DelegateClassLoader child = new DatadogClassLoader.DelegateClassLoader(testJarLocation,
      "child", bootstrapProxy, null, parent)

    when:
    Class<?> z = child.loadClass("x.y.z.Z")

    then:
    z.getClassLoader() == child
  }

  def "test class not found"() {
    setup:
    DatadogClassLoader.BootstrapClassLoaderProxy bootstrapProxy =
      new DatadogClassLoader.BootstrapClassLoaderProxy()
    DatadogClassLoader parent = new DatadogClassLoader(testJarLocation, "parent", bootstrapProxy, null)
    DatadogClassLoader.DelegateClassLoader child = new DatadogClassLoader.DelegateClassLoader(testJarLocation,
      "child", bootstrapProxy, null, parent)

    when:
    child.loadClass("not.found.NotFound")

    then:
    thrown ClassNotFoundException
  }

  def "test parent classloader successfully loads classes concurrently"() {
    given:
    assumeTrue(isJavaVersionAtLeast(8))
    DatadogClassLoader.BootstrapClassLoaderProxy bootstrapProxy = new DatadogClassLoader.BootstrapClassLoaderProxy()

    DatadogClassLoader parent = new DatadogClassLoader(testJarLocation, "parent", bootstrapProxy, null)
    parent.findLoadedClass(_) >> null

    DatadogClassLoader.DelegateClassLoader child = new DatadogClassLoader.DelegateClassLoader(testJarLocation,
      "child", bootstrapProxy, null, parent)

    when:
    ExecutorService executorService = Executors.newCachedThreadPool()
    List<Future<Void>> futures = new ArrayList<>()

    for (int i = 0; i < 100; i++) {
      futures.add(executorService.submit(new Callable<Void>() {
        Void call() {
          child.loadClass("a.A")
          return null
        }
      }))
    }

    for (Future<Void> callable : futures) {
      try {
        callable.get()
      } catch (ExecutionException ex) {
        throw ex.getCause()
      }
    }

    then:
    noExceptionThrown()

  }

  def "test delegate classloader successfully loads classes concurrently"() {
    given:
    assumeTrue(isJavaVersionAtLeast(8))
    DatadogClassLoader.BootstrapClassLoaderProxy bootstrapProxy = new DatadogClassLoader.BootstrapClassLoaderProxy()
    DatadogClassLoader parent = new DatadogClassLoader(testJarLocation, "parent", bootstrapProxy, null)

    DatadogClassLoader.DelegateClassLoader child = new DatadogClassLoader.DelegateClassLoader(testJarLocation,
      "child", bootstrapProxy, null, parent)
    child.findLoadedClass(_) >> null

    ExecutorService executorService = Executors.newCachedThreadPool()
    List<Future<Void>> futures = new ArrayList<>()

    when:
    for (int i = 0; i < 100; i++) {
      futures.add(executorService.submit(new Callable<Void>() {
        Void call() {
          child.loadClass("x.X")
          return null
        }
      }))
    }

    for (Future<Void> callable : futures) {
      try {
        callable.get()
      } catch (ExecutionException ex) {
        throw ex.getCause()
      }
    }

    then:
    noExceptionThrown()

  }
}
