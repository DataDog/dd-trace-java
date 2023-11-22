import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule


class MultipartInstrumentationTest extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'test commons fileupload2 ParameterParser.parse'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final content = "Content-Disposition: form-data; name=\"file\"; filename=\"=?ISO-8859-1?B?SWYgeW91IGNhbiByZWFkIHRoaXMgeW8=?= =?ISO-8859-2?B?dSB1bmRlcnN0YW5kIHRoZSBleGFtcGxlLg==?=\"\r\n"
    final parser = clazz.newInstance()

    when:
    parser.parse(content, new char[]{
      ',', ';'
    })

    then:
    1 * module.taint(null, 'file', SourceTypes.REQUEST_MULTIPART_PARAMETER, 'name')
    1 * module.taint(null, _, SourceTypes.REQUEST_MULTIPART_PARAMETER, 'filename')
    0 * _

    where:
    clazz | _
    org.apache.commons.fileupload.ParameterParser | _
    org.apache.tomcat.util.http.fileupload.ParameterParser | _
  }
}
