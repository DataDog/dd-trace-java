package datadog.trace.instrumentation.drools

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.config.inversion.ConfigHelper
import example.Person
import org.kie.api.KieServices
import org.kie.api.builder.KieFileSystem
import org.kie.api.runtime.KieContainer
import org.kie.api.runtime.KieSession

class DroolsClassLoaderExclusionTest extends InstrumentationSpecification {
  static ConfigHelper.StrictnessPolicy strictness

  @Override
  protected void configurePreAgent() {
    // this is required otherwise checks will fail...
    strictness = ConfigHelper.get().configInversionStrictFlag()
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST)
    super.configurePreAgent()
  }

  def cleanup() {
    ConfigHelper.get().setConfigInversionStrict(strictness)
  }

  def "should not instrument drools generated classes"() {
    setup:
    KieServices ks = KieServices.Factory.get()

    KieFileSystem kfs = ks.newKieFileSystem()

    kfs.write(
      "src/main/resources/example/rules.drl",
      ks.getResources().newClassPathResource("example/rules.drl")
      )

    ks.newKieBuilder(kfs).buildAll()
    KieContainer kc = ks.newKieContainer(ks.getRepository().getDefaultReleaseId())
    KieSession ksession = kc.newKieSession()

    when:
    Person john = new Person("John", 20)
    Person bob = new Person("Bob", 15)

    ksession.insert(john)
    ksession.insert(bob)
    int fired = ksession.fireAllRules()

    then:
    fired == 1
    !bob.isAdult()
    john.isAdult()

    and:
    // assert we do not transform the generated rule class (RuleInstrumentation would but the classloader should be ignored)
    TRANSFORMED_CLASSES_TYPES.findAll { it.getName().startsWith("example.") }.isEmpty()

    cleanup:
    ksession?.dispose()
  }
}

