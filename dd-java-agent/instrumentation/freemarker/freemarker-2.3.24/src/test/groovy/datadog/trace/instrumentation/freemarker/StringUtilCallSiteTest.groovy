package datadog.trace.instrumentation.freemarker

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestStringUtilSuite
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestInstancePostProcessor

@ExtendWith(TestSourceFileExtension)
class StringUtilCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  //  void 'test #method'() {
  //    given:
  //    final module = Mock(PropagationModule)
  //    InstrumentationBridge.registerIastModule(module)
  //
  //    when:
  //    final result = TestStringUtilSuite.&"$method".call(args)
  //
  //    then:
  //    result == expected
  //    1 * module.taintStringIfTainted(_ as String, args[0], false, VulnerabilityMarks.XSS_MARK)
  //    0 * _
  //
  //    where:
  //    method                | args                                                            | expected
  //    'HTMLEnc'             | ['<htmlTag>"escape this < </htmlTag>']                          | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'
  //    'XMLEnc'              | ['<xmlTag>"escape this < </xmlTag>']                            | '&lt;xmlTag&gt;&quot;escape this &lt; &lt;/xmlTag&gt;'
  //    'XHTMLEnc'            | ['<htmlTag>"escape this < </htmlTag>']                          | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'
  //    'javaStringEnc'       | ['<script>function a(){console.log("escape this < ")}<script>'] | '<script>function a(){console.log(\\"escape this < \\")}<script>'
  //    'javaScriptStringEnc' | ['<script>function a(){console.log("escape this < ")}<script>'] | '<script>function a(){console.log(\\"escape this < \\")}<script>'
  //    'jsonStringEnc'       | ['["a":{"b":2}]']                                               | '[\\"a\\":{\\"b\\":2}]'
  //  }

  void 'test #method with null args'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    System.out.println("---running failing test---")
    TestStringUtilSuite.&"$method".call(null)

    then:
    def thrownException = thrown (Exception)
    assert  thrownException.stackTrace[0].getClassName().startsWith('Xfreemarker')
    0 * _

    where:
    method                | ex
    'HTMLEnc'             | _
    //    'XMLEnc'              | _
    //    'XHTMLEnc'            | _
    //    'javaStringEnc'       | _
    //    'javaScriptStringEnc' | _
    //    'jsonStringEnc'       | _
  }
}

class TestSourceFileExtension implements TestInstancePostProcessor {
  TestSourceFileExtension() {
    System.out.println("---TestSourceFileExtension initialized.---")
  }

  @Override
  void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    System.out.println("---in postProcessTestInstance---")
    getTestData(context)
  }

  private static void getTestData(ExtensionContext context) {
    System.out.println("---in getTestData---")
    String testClassName = context.getTestClass().get().getSimpleName()
    String testMethodName = context.getTestMethod().get().getName()
    String requiredTestClassName = context.getRequiredTestClass().getName()
    String requiredTestMethodName = context.getRequiredTestMethod().getName()

    System.out.println("--------------------------")
    System.out.println("testClassName: " + testClassName)
    System.out.println("testMethodName: " + testMethodName)
    System.out.println("requiredTestClassName: " + requiredTestClassName)
    System.out.println("requiredTestMethodName: " + requiredTestMethodName)
    System.out.println("--------------------------")
  }
}
