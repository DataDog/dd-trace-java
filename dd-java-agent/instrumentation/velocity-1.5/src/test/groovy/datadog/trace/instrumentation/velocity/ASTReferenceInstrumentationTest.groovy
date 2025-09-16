package datadog.trace.instrumentation.velocity

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import org.apache.velocity.Template
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.tools.generic.EscapeTool

class ASTReferenceInstrumentationTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test ASTReference execute (insecure)'() {
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    VelocityEngine velocity = new VelocityEngine()
    velocity.setProperty(
      RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
      "org.apache.velocity.runtime.log.NullLogChute")
    velocity.init()
    Template template = velocity.getTemplate("src/test/resources/velocity-astreference-insecure.vm")

    VelocityContext context = new VelocityContext()
    context.put("param", param)

    when:
    template.merge(context, Mock(FileWriter))

    then:
    1 * module.onXss(_, _, _)

    where:
    param << ["<script>alert(1)</script>", "name"]
  }

  void 'test ASTReference execute (secure)'() {
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    VelocityEngine velocity = new VelocityEngine()
    velocity.setProperty(
      RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
      "org.apache.velocity.runtime.log.NullLogChute")
    velocity.init()
    Template template = velocity.getTemplate("src/test/resources/velocity-astreference-secure.vm")

    VelocityContext context = new VelocityContext()
    context.put("esc", new EscapeTool())
    context.put("param", param)

    when:
    template.merge(context, Mock(FileWriter))

    then:
    0 * module.onXss(_, _, _)

    where:
    param << ["<script>alert(1)</script>", "name"]
  }
}
