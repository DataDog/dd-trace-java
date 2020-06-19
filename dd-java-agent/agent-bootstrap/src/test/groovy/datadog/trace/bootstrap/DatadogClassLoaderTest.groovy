package datadog.trace.bootstrap

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.Phaser
import java.util.concurrent.TimeUnit

import static org.junit.Assume.assumeFalse

class DatadogClassLoaderTest extends Specification {
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
    assumeFalse(System.getProperty("java.version").contains("1.7"))
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
    assumeFalse(System.getProperty("java.version").contains("1.7"))
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
    assumeFalse(System.getProperty("java.version").contains("1.7"))
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
}
