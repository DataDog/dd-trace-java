import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.jbossmodules.ModuleNameHelper
import org.jboss.modules.DependencySpec
import org.jboss.modules.Module
import org.jboss.modules.ModuleSpec

class ModuleNameExtractionTest extends InstrumentationSpecification {
  def "should be able to extract a module name"() {
    given:

    ModuleSpec spec = ModuleSpec.build("dummy")
      .addDependency(DependencySpec.createLocalDependencySpec())
      .create()

    Module module = new Module(spec, Module.getBootModuleLoader())

    expect:
    module != null
    module.getName() == ModuleNameHelper.extractModuleName(module)
  }
}
