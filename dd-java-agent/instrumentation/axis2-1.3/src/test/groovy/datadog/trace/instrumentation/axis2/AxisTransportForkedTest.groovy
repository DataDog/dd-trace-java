package datadog.trace.instrumentation.axis2

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.axiom.soap.SOAPFactory
import org.apache.axis2.addressing.EndpointReference
import org.apache.axis2.context.ConfigurationContext
import org.apache.axis2.description.AxisService
import org.apache.axis2.description.TransportOutDescription
import org.apache.axis2.engine.AxisConfiguration
import org.apache.axis2.engine.AxisEngine
import org.apache.axis2.receivers.RawXMLINOnlyMessageReceiver
import org.apache.axis2.receivers.RawXMLINOutMessageReceiver
import spock.lang.Shared
import test.TestSender
import test.TestService

import javax.xml.namespace.QName

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static org.apache.axiom.om.OMAbstractFactory.getSOAP11Factory
import static org.apache.axis2.Constants.SERVICE_CLASS
import static org.apache.axis2.context.ConfigurationContextFactory.createConfigurationContextFromFileSystem
import static org.apache.axis2.deployment.util.Utils.fillAxisService
import static org.apache.axis2.description.WSDL2Constants.MEP_URI_IN_ONLY
import static org.apache.axis2.description.WSDL2Constants.MEP_URI_IN_OUT
import static org.apache.axis2.description.WSDL2Constants.MEP_URI_ROBUST_IN_ONLY

class AxisTransportForkedTest extends InstrumentationSpecification {

  @Shared
  SOAPFactory soapFactory = getSOAP11Factory()

  @Shared
  ConfigurationContext serverContext

  @Shared
  AxisConfiguration serverConfig

  @Shared
  TransportOutDescription transportOut

  @Shared
  AxisService testService

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(TraceInstrumentationConfig.AXIS_TRANSPORT_CLASS_NAME, 'test.TestSender')
  }

  def setupSpec() throws Exception {
    serverContext = createConfigurationContextFromFileSystem(null)
    serverConfig = serverContext.getAxisConfiguration()

    transportOut = new TransportOutDescription('SOAPOverHTTP')
    transportOut.setSender(new TestSender())
    serverConfig.addTransportOut(transportOut)

    // add message receivers for some standard flows
    serverConfig.addMessageReceiver(MEP_URI_IN_ONLY, new RawXMLINOnlyMessageReceiver())
    serverConfig.addMessageReceiver(MEP_URI_IN_OUT, new RawXMLINOutMessageReceiver())
    serverConfig.addMessageReceiver(MEP_URI_ROBUST_IN_ONLY, new RawXMLINOutMessageReceiver())

    // register our simple test service
    testService = new AxisService('TestService')
    testService.addParameter(SERVICE_CLASS, TestService.name)
    fillAxisService(testService, serverConfig, null, null)
    serverConfig.addService(testService)
  }

  def "test context propagated to transport headers"() {
    when:
    def message1 = testMessage()
    message1.setSoapAction('testAction')
    message1.setProperty("TRANSPORT_HEADERS", ["foo":"bar"] as HashMap)
    def message2 = testMessage()
    // no action, expect span to use testDestination
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      AxisEngine.send(message1)
      AxisEngine.send(message2)
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(5) {
        testSpan(it, 0)
        axisSpan(it, 1, 'http://my-host:8080/TestService/testAction', span(0))
        transportSpan(it, 2, 'http://my-host:8080/TestService/testAction', span(1))
        axisSpan(it, 3, 'testAction', span(0))
        transportSpan(it, 4, 'testAction', span(3))
      }
    }
  }

  def testMessage() {
    def message = serverContext.createMessageContext()

    // create a simple message for our test service
    message.setTo(new EndpointReference('http://my-host:8080/TestService/testAction'))
    message.setFaultTo(new EndpointReference('http://my-host:8080/TestService/testFault'))
    message.setEnvelope(soapFactory.getDefaultEnvelope())
    message.setTransportOut(transportOut)

    // create an operation context (normally the transport layer would do this)
    def operationContext = serverContext
      .createServiceGroupContext(testService.getAxisServiceGroup())
      .getServiceContext(testService)
      .createOperationContext(new QName('testAction'))
    message.setOperationContext(operationContext)

    return message
  }

  def testSpan(TraceAssert trace, int index, Object parentSpan = null) {
    trace.span {
      serviceName "testSpan"
      operationName "test"
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      topLevel parentSpan == null
      tags {
        defaultTags()
      }
    }
  }

  def axisSpan(TraceAssert trace, int index, String soapAction, Object parentSpan, Object error = null) {
    trace.span {
      serviceName "testSpan"
      operationName "axis2.message"
      resourceName soapAction
      spanType DDSpanTypes.SOAP
      errored error != null
      measured true
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      topLevel parentSpan == null
      tags {
        if (error instanceof Exception) {
          "error.message" error.message
          "error.type" { it == error.class.name }
          "error.stack" String
        }
        "$Tags.COMPONENT" "axis2"
        defaultTags()
      }
    }
  }

  def axisSpanWithError(TraceAssert trace, int index, String soapAction, Object parentSpan) {
    axisSpan(trace, index, soapAction, parentSpan, true)
  }

  def axisSpanWithException(TraceAssert trace, int index, String soapAction, Object parentSpan, Exception error) {
    axisSpan(trace, index, soapAction, parentSpan, error)
  }

  def transportSpan(TraceAssert trace, int index, String soapAction, Object parentSpan, Object error = null) {
    trace.span {
      serviceName "testSpan"
      operationName "axis2.transport"
      resourceName soapAction
      spanType DDSpanTypes.SOAP
      errored error != null
      measured true
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      topLevel parentSpan == null
      tags {
        if (error instanceof Exception) {
          "error.message" error.message
          "error.type" { it == error.class.name }
          "error.stack" String
        }
        "$Tags.COMPONENT" "axis2"
        "$Tags.SPAN_KIND" "client"
        "$Tags.HTTP_STATUS" 200
        "$Tags.PEER_HOSTNAME" "my-host"
        "$Tags.PEER_PORT" 8080
        defaultTags()
      }
    }
  }
}
