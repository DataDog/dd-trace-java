package datadog.trace.agent.tooling

import com.google.common.io.ByteStreams
import datadog.trace.agent.test.tooling.DummyService
import lombok.extern.slf4j.Slf4j
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
import spock.lang.Specification

import java.util.concurrent.TimeUnit

class DDCachingPoolStrategyTest extends Specification {

  def classLoader = new URLClassLoader()
  def classFileLocator = new DummyClassFileLocator()

  def "aaa()"() {
    setup:
    def typePoolCacheProvider = new TypePool.CacheProvider() {

      @Override
      TypePool.Resolution find(String name) {
        println "Find $name"
        return null
      }

      @Override
      TypePool.Resolution register(String name, TypePool.Resolution resolution) {
        println "Register $name"
        return null
      }

      @Override
      void clear() {
        println "Clear"
      }
    }

    def typePool = new TypePool.Default.WithLazyResolution(
      typePoolCacheProvider, classFileLocator, TypePool.Default.ReaderMode.FAST)

    def described = typePool.describe(DummyService.name)
    def luca = described.isResolved()
    def aaa = described.resolve()
  }

//  def "with no cleanup thread cache is still expired()"() {
//    setup:
//    def poolStrategy = new DDCachingPoolStrategy(1, 1, TimeUnit.SECONDS)
//
//    when:
//    def pool1 = poolStrategy.typePool(classFileLocator, classLoader)
//    def resolutionInvocation1 = pool1.describe(DummyService.getName())
//    resolutionInvocation1.resolve()
////    resolutionInvocation1.declaredMethods
////    def pool2 = poolStrategy.typePool(classFileLocator, classLoader)
////    def resolutionInvocation2 = pool2.describe(DummyService.getName()).resolve()
////    resolutionInvocation2.declaredMethods
////    def a = new DummyService()
//
//    then:
//    1 == 1
////    resolutionInvocation1 == resolutionInvocation2
//
////    when:
////    sleep(100)
////    def poolBeforeExpire = poolStrategy.typePool(classFileLocator, classLoader)
////    def resolutionInvocationBeforeExpire = poolBeforeExpire.describe(DummyService.getName()).resolve()
////    resolutionInvocationBeforeExpire.declaredMethods
////
////    then:
////    resolutionInvocation1 == resolutionInvocationBeforeExpire
////
////    when:
////    sleep(1500)
////    def poolAfterExpire = poolStrategy.typePool(classFileLocator, classLoader)
////    def resolutionInvocationAfterExpire = poolAfterExpire.describe(DummyService.getName()).resolve()
////    resolutionInvocationAfterExpire.declaredMethods
////
////    then:
////    resolutionInvocation1 != resolutionInvocationAfterExpire
//  }

  class DummyClassFileLocator implements ClassFileLocator {

    @Override
    Resolution locate(String name) throws IOException {
      return new DummyResolution()
    }

    @Override
    void close() throws IOException {}
  }

  class DummyResolution implements ClassFileLocator.Resolution {

    @Override
    boolean isResolved() {
      return false
    }

    @Override
    byte[] resolve() {
      String className = DummyService.name
      String classAsPath = className.replace('.', '/') + ".class"
      InputStream stream = DummyService.getClassLoader().getResourceAsStream(classAsPath)
      return ByteStreams.toByteArray(stream)
    }
  }
}
