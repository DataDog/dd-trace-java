package datadog.telemetry.dependency

import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.IllegalClassFormatException
import java.lang.instrument.Instrumentation
import java.security.CodeSigner
import java.security.CodeSource
import java.security.ProtectionDomain

import static org.hamcrest.Matchers.is
import static org.junit.Assert.assertThat

class DependencyServiceSpecification extends DepSpecification {
  DependencyService depService = new DependencyService()

  void 'no uris pushed should result in empty list'() {
    when:
    depService.resolveOneDependency()

    then:
    def dependencies = depService.drainDeterminedDependencies()
    dependencies.isEmpty()
  }

  void 'null uri results in NPE'() {
    when:
    depService.addURL(null)

    then:
    thrown NullPointerException
  }

  void 'class files are ignored as dependencies'() {
    when:
    depService.addURL(new URL('file:///tmp/toto.class'))
    depService.resolveOneDependency()

    then:
    depService.drainDeterminedDependencies().isEmpty()
  }

  void 'add missing jar url dependency'() throws URISyntaxException {
    when:
    // this URI comes from a spring-boot application
    URL url = new URL('jar:file:/tmp//spring-petclinic-2.1.0.BUILD-SNAPSHOT.jar!/BOOT-INF/lib/spring-boot-2.1.0.BUILD-SNAPSHOT.jar!//')
    depService.addURL(url)
    depService.resolveOneDependency()

    then:
    depService.drainDeterminedDependencies().isEmpty()
  }

  void 'invalid jar names are ignored'() {
    when:
    depService.addURL(new File(".zip").toURL())
    depService.resolveOneDependency()

    then:
    depService.drainDeterminedDependencies().isEmpty()
  }

  void 'build dependency set from known jar'() {
    when:
    File junitJar = getJar('junit-4.12.jar')

    depService.addURL(new File(junitJar.getAbsolutePath()).toURL())
    depService.resolveOneDependency()

    then:
    def set = depService.drainDeterminedDependencies() as Set
    assertThat(set.size(), is(1))

    assertThat(set.first().name, is('junit'))
    assertThat(set.first().version, is('4.12'))
  }

  void 'build dependency set from a fat jar'() {
    when:
    File budgetappJar = getJar('budgetapp.jar')

    depService.addURL(new File(budgetappJar.getAbsolutePath()).toURL())
    depService.resolveOneDependency()

    then:
    def set = depService.drainDeterminedDependencies() as Set
    assertThat(set.size(), is(105))
  }

  void 'build dependency set from a small fat jar'() {
    when:
    File budgetappJar = getJar('budgetappreduced.jar')

    depService.addURL(new File(budgetappJar.getAbsolutePath()).toURL())
    depService.resolveOneDependency()

    then:
    def set = depService.drainDeterminedDependencies() as Set
    assertThat(set.size(), is(2))
    assertThat(set.first().name, is('cglib:cglib'))
    assertThat(set.first().version, is('3.2.4'))
    assertThat(set.last().name, is('org.yaml:snakeyaml'))
    assertThat(set.last().version, is('1.17'))
  }

  void 'build dependency set from a small fat jar with one incorrect pom.properties'() {
    when:
    File budgetappJar = getJar('budgetappreducedbadproperties.jar')

    depService.addURL(new File(budgetappJar.getAbsolutePath()).toURL())
    depService.resolveOneDependency()

    then:
    def set = depService.drainDeterminedDependencies() as Set
    assertThat(set.size(), is(1))
    assertThat(set.first().name, is('org.yaml:snakeyaml'))
    assertThat(set.first().version, is('1.17'))
  }

  void 'transformer invalid code source'() throws IllegalClassFormatException, MalformedURLException {
    Instrumentation instrumentation = Mock()
    ClassFileTransformer t

    when:
    depService.installOn(instrumentation)

    then:
    1 * instrumentation.addTransformer(_) >> { t = it[0] }
    t != null


    when:
    // null protection domain
    t.transform(null, null, null, null, null)
    depService.resolveOneDependency()

    then:
    depService.drainDeterminedDependencies().isEmpty()

    when:
    // null code source
    ProtectionDomain protectionDomain1 = new ProtectionDomain(null, null, null, null)
    t.transform(null, null, null, protectionDomain1, null)
    depService.resolveOneDependency()

    then:
    depService.drainDeterminedDependencies().isEmpty()

    when:
    // null code source location
    CodeSource codeSource2 = new CodeSource(null, (CodeSigner[]) null)
    ProtectionDomain protectionDomain2 = new ProtectionDomain(codeSource2, null, null, null)
    t.transform(null, null, null, protectionDomain2, null)
    depService.resolveOneDependency()

    then:
    codeSource2.location == null
    depService.drainDeterminedDependencies().isEmpty()

    when:
    // null or invalid URI syntax
    URL url3 = new URL('http:// ') // this url is known to not be a valid URI
    CodeSource codeSource3 = new CodeSource(url3, (CodeSigner[]) null)
    ProtectionDomain protectionDomain3 = new ProtectionDomain(codeSource3, null, null, null)
    t.transform(null, null, null, protectionDomain3, null)
    depService.resolveOneDependency()

    then:
    depService.drainDeterminedDependencies().isEmpty()
  }
}
