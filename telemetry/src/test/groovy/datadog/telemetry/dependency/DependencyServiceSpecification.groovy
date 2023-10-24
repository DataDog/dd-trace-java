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

  void 'no dependencies result in empty list'() {
    when:
    depService.resolveOneDependency()

    then:
    def dependencies = depService.drainDeterminedDependencies()
    dependencies.isEmpty()
  }

  void 'ignore null dependencies'() {
    when:
    depService.add(null)

    then:
    def dependencies = depService.drainDeterminedDependencies()
    dependencies.isEmpty()
  }

  void 'ignore unsupported cases'() {
    when:
    depService.add(new URL(url))
    depService.resolveOneDependency()
    def dependencies = depService.drainDeterminedDependencies()

    then:
    dependencies.isEmpty()

    where:
    url                          | _
    'https://example.com/my.jar' | _
    'file:///foo.class'          | _
  }

  void 'add missing jar url dependency'() {
    when:
    depService.add(new URL(url))
    depService.resolveOneDependency()

    then:
    depService.drainDeterminedDependencies().isEmpty()

    where:
    url                                                                                                              | _
    'jar:file:/tmp//spring-petclinic-2.1.0.BUILD-SNAPSHOT.jar!/BOOT-INF/lib/spring-boot-2.1.0.BUILD-SNAPSHOT.jar!//' | _
    'file:/C:/Program Files/IBM/WebSphere/AppServer_1/plugins/com.ibm.cds_2.1.0.jar'                                 | _
    'jar:file:/Users/test/spring-petclinic.jar!/BOOT-INF/classes!/'                                                  | _
  }

  void 'invalid jar names are ignored'() {
    when:
    def url = new URL('file:/tmp/foo.zip')
    depService.add(url)
    depService.resolveOneDependency()

    then:
    depService.drainDeterminedDependencies().isEmpty()
  }

  void 'build dependency set from known jar'() {
    when:
    def junitJar = getJar('junit-4.12.jar')
    def url = new File(junitJar.getAbsolutePath()).toURL()
    depService.add(url)
    depService.resolveOneDependency()

    then:
    def set = depService.drainDeterminedDependencies() as Set
    assertThat(set.size(), is(1))
    assertThat(set.first().name, is('junit'))
    assertThat(set.first().version, is('4.12'))
  }

  void 'build dependency set from a fat jar'() {
    given:
    def budgetappJar = getJar('budgetapp.jar')
    def url = budgetappJar.toURL()

    when:
    depService.add(url)
    depService.resolveOneDependency()

    then:
    def set = depService.drainDeterminedDependencies() as Set
    assertThat(set.size(), is(105))
  }

  void 'build dependency set from a small fat jar'() {
    given:
    def budgetappJar = getJar('budgetappreduced.jar')
    def url = budgetappJar.toURL()

    when:
    depService.add(url)
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
    given:
    def budgetappJar = getJar('budgetappreducedbadproperties.jar')
    def url = budgetappJar.toURL()

    when:
    depService.add(url)
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
