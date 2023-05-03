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

class DatadogClassLoaderTest extends DDSpecification {
  @Shared
  URL testJarLocation = new File("src/test/resources/classloader-test-jar/testjar-jdk8").toURI().toURL()

  @Shared
  URL nestedTestJarLocation = new File("src/test/resources/classloader-test-jar/jar-with-nested-classes-jdk8").toURI().toURL()

  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  def "agent classloader does not lock classloading around instance"() {
    setup:
    def className1 = 'some/class/Name1'
    def className2 = 'some/class/Name2'
    final DatadogClassLoader ddLoader = new DatadogClassLoader()
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

  def "agent classloader successfully loads classes concurrently"() {
    given:
    DatadogClassLoader ddLoader = new DatadogClassLoader(testJarLocation, null)

    when:
    ExecutorService executorService = Executors.newCachedThreadPool()
    List<Future<Void>> futures = new ArrayList<>()

    for (int i = 0; i < 100; i++) {
      futures.add(executorService.submit(new Callable<Void>() {
          Void call() {
            ddLoader.loadClass("a.A")
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

  def "test load nested classes and call getEnclosingClass"() {
    given:
    DatadogClassLoader ddLoader = new DatadogClassLoader(nestedTestJarLocation, null)

    when:
    Class<?> klass = ddLoader.loadClass("p.EnclosingClass\$StaticInnerClass")
    String simpleName = klass.getSimpleName()

    then:
    simpleName == "StaticInnerClass"

    when:
    Class<?> enclosing = klass.getEnclosingClass()

    then:
    enclosing.getSimpleName() == "EnclosingClass"
  }
}
