package datadog.telemetry.dependency

import java.security.CodeSource
import java.security.ProtectionDomain

class LocationsCollectingTransformerSpecification extends DepSpecification {

  DependencyServiceImpl depService = new DependencyServiceImpl()

  LocationsCollectingTransformer transformer = new LocationsCollectingTransformer(depService)

  void 'no dependency for agent jar'() {
    when:
    transformer.transform(null, null, null, getClass().getProtectionDomain(), null)

    then:
    depService.resolveOneDependency()
    def dependencies = depService.drainDeterminedDependencies()
    dependencies.isEmpty()
  }

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
}
