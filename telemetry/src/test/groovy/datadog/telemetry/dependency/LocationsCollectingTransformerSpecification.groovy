package datadog.telemetry.dependency

import spock.lang.Timeout

import java.security.CodeSource
import java.security.ProtectionDomain
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class LocationsCollectingTransformerSpecification extends DepSpecification {

  DependencyService depService = new DependencyService()

  LocationsCollectingTransformer transformer = new LocationsCollectingTransformer(depService)

  void 'no dependency if null protection domain'() {
    when:
    transformer.transform(null, null, null, null, null)

    then:
    depService.resolveOneDependency()
    def dependencies = depService.drainDeterminedDependencies()
    dependencies.isEmpty()
  }

  void 'no dependency if null code source'() {
    when:
    ProtectionDomain domain = new ProtectionDomain(null, null)
    transformer.transform(null, null, null, domain, null)

    then:
    depService.resolveOneDependency()
    def dependencies = depService.drainDeterminedDependencies()
    dependencies.isEmpty()
  }

  void 'no dependency if null location'() {
    when:
    CodeSource source = new CodeSource(null, (java.security.cert.Certificate[])null)
    ProtectionDomain domain = new ProtectionDomain(source, null)
    transformer.transform(null, null, null, domain, null)

    then:
    depService.resolveOneDependency()
    def dependencies = depService.drainDeterminedDependencies()
    dependencies.isEmpty()
  }

  void 'one dependency if normal url'() {
    when:
    CodeSource source = new CodeSource(getJar('bson-4.2.0.jar').toURI().toURL(), (java.security.cert.Certificate[])null)
    ProtectionDomain domain = new ProtectionDomain(source, null)
    transformer.transform(null, null, null, domain, null)

    then:
    depService.resolveOneDependency()
    def dependencies = depService.drainDeterminedDependencies()
    dependencies.size()==1
  }

  void 'single dependency if repeated protection domain'() {
    when:
    CodeSource source = new CodeSource(getJar('bson-4.2.0.jar').toURI().toURL(), (java.security.cert.Certificate[])null)
    ProtectionDomain domain = new ProtectionDomain(source, null)
    transformer.transform(null, null, null, domain, null)
    transformer.transform(null, null, null, domain, null)

    then:
    depService.resolveOneDependency()
    def dependencies = depService.drainDeterminedDependencies()
    dependencies.size()==1
  }

  void 'multiple dependencies'() {
    given:
    def nDomains = 1000
    def domains = new ArrayList<ProtectionDomain>()
    (1..nDomains).each {
      CodeSource source = new CodeSource(new URL("file:///bson-${it}.jar"), (java.security.cert.Certificate[])null)
      ProtectionDomain domain = new ProtectionDomain(source, null)
      domains.add(domain)
    }

    and:
    def depService = Mock(DependencyService)
    def transformer = new LocationsCollectingTransformer(depService)

    when:
    domains.each {
      transformer.transform(null, null, null, it, null)
    }

    then:
    nDomains * depService.addURL(_)
  }

  @Timeout(10)
  void 'multiple dependencies with concurrency'() {
    given:
    def threads = 16
    def executor = Executors.newFixedThreadPool(threads)
    def latch = new CountDownLatch(threads)

    and:
    def nDomains = 3000
    def domains = new ArrayBlockingQueue<ProtectionDomain>(nDomains)
    (1..nDomains).each {
      CodeSource source = new CodeSource(new URL("file:///bson-${it}.jar"), (java.security.cert.Certificate[])null)
      ProtectionDomain domain = new ProtectionDomain(source, null)
      domains.add(domain)
    }

    and:
    def depService = Mock(DependencyService)
    def transformer = new LocationsCollectingTransformer(depService)

    when:
    def futures = (1..threads).collect {
      executor.submit {
        latch.countDown()
        latch.await()
        ProtectionDomain domain = null
        while ((domain = domains.poll()) != null) {
          transformer.transform(null, null, null, domain, null)
        }
      }
    }
    futures.each { it.get() }

    then:
    nDomains * depService.addURL(_)
    0 * _

    cleanup:
    executor?.shutdownNow()
  }

  @Timeout(10)
  void 'single dependencies with concurrency'() {
    given:
    def threads = 16
    def executor = Executors.newFixedThreadPool(threads)
    def latch = new CountDownLatch(threads)

    and:
    def nDomains = 3000
    CodeSource source = new CodeSource(new URL("file:///bson-1.jar"), (java.security.cert.Certificate[])null)
    ProtectionDomain domain = new ProtectionDomain(source, null)

    and:
    def depService = Mock(DependencyService)
    def transformer = new LocationsCollectingTransformer(depService)

    when:
    def futures = (1..threads).collect {
      executor.submit {
        latch.countDown()
        latch.await()
        for (int i = 0; i < nDomains; i++) {
          transformer.transform(null, null, null, domain, null)
        }
      }
    }
    futures.each { it.get() }

    then:
    (1..threads) * depService.addURL(_)
    0 * _

    cleanup:
    executor?.shutdownNow()
  }
}
