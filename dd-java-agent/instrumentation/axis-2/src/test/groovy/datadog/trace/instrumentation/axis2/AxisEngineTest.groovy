package datadog.trace.instrumentation.axis2

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.axiom.soap.SOAPFactory
import org.apache.axis2.addressing.EndpointReference
import org.apache.axis2.context.ConfigurationContext
import org.apache.axis2.context.MessageContext
import org.apache.axis2.description.AxisService
import org.apache.axis2.description.TransportInDescription
import org.apache.axis2.description.TransportOutDescription
import org.apache.axis2.engine.AxisConfiguration
import org.apache.axis2.engine.AxisEngine
import org.apache.axis2.engine.Handler.InvocationResponse
import org.apache.axis2.engine.Phase
import org.apache.axis2.handlers.AbstractHandler
import org.apache.axis2.receivers.RawXMLINOnlyMessageReceiver
import org.apache.axis2.receivers.RawXMLINOutMessageReceiver
import org.apache.axis2.transport.http.SimpleHTTPServer
import org.apache.axis2.transport.local.LocalTransportReceiver
import org.apache.axis2.transport.local.LocalTransportSender
import spock.lang.Shared
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
import static org.apache.axis2.engine.Handler.InvocationResponse.CONTINUE
import static org.apache.axis2.engine.Handler.InvocationResponse.SUSPEND
import static org.apache.axis2.util.MessageContextBuilder.createFaultMessageContext

class AxisEngineTest extends InstrumentationSpecification {

  @Shared
  SOAPFactory soapFactory = getSOAP11Factory()

  @Shared
  ConfigurationContext serverContext

  @Shared
  AxisConfiguration serverConfig

  @Shared
  TransportInDescription transportIn

  @Shared
  TransportOutDescription transportOut

  @Shared
  AxisService testService

  static final String TRIGGER_FAIL = 'TRIGGER FAIL'
  static final String TRIGGER_PAUSE = 'TRIGGER PAUSE'

  @Shared
  def testPhase = new Phase('TestPhase') { {
      addHandler(new AbstractHandler() {
        @Override
        InvocationResponse invoke(MessageContext messageContext) {
          if (messageContext.getProperty(TRIGGER_FAIL)) {
            throw new RuntimeException('Internal Error')
          }
          return CONTINUE
        }
      })
      addHandler(new AbstractHandler() {
        @Override
        InvocationResponse invoke(MessageContext messageContext) {
          if (messageContext.getProperty(TRIGGER_PAUSE)) {
            return SUSPEND
          }
          return CONTINUE
        }
      })
    }
  }

  def setupSpec() throws Exception {
    serverContext = createConfigurationContextFromFileSystem(null)
    serverConfig = serverContext.getAxisConfiguration()

    // use local transport for our test messages
    LocalTransportReceiver.CONFIG_CONTEXT = new ConfigurationContext(serverConfig)
    LocalTransportReceiver.CONFIG_CONTEXT.setServicePath('services')
    LocalTransportReceiver.CONFIG_CONTEXT.setContextRoot('local:/')
    transportIn = new TransportInDescription('local')
    transportIn.setReceiver(new SimpleHTTPServer(serverContext, PortUtils.randomOpenPort()))
    serverConfig.addTransportIn(transportIn)
    transportOut = new TransportOutDescription('local')
    transportOut.setSender(new LocalTransportSender())
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

    // custom phase to help test message handling
    serverConfig.getInFlowPhases().add(0, testPhase)
    serverConfig.getOutFlowPhases().add(0, testPhase)
  }

  def "test no traces without surrounding operation"() {
    when:
    def message1 = testMessage()
    message1.setSoapAction('testAction')
    AxisEngine.send(message1)

    def message2 = testMessage()
    // no action, expect span to use testDestination
    AxisEngine.send(message2)

    def message3 = testMessage()
    message3.setSoapAction('testAction')
    AxisEngine.receive(message3)

    def message4 = testMessage()
    // no action, expect span to use testDestination
    AxisEngine.receive(message4)

    then:
    assertTraces(0) {}
  }

  def "test send"() {
    when:
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      def message1 = testMessage()
      message1.setSoapAction('testAction')
      AxisEngine.send(message1)

      def message2 = testMessage()
      // no action, expect span to use testDestination
      AxisEngine.send(message2)
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(3) {
        testSpan(it, 0)
        axisSpan(it, 1, 'local://services/TestService/testAction', span(0))
        axisSpan(it, 2, 'testAction', span(0))
      }
    }
  }

  def "test pause+resume send"() {
    when:
    def message = testMessage()
    message.setProperty(TRIGGER_PAUSE, true)
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      AxisEngine.send(message)
    }
    span0.finish()

    then:
    assertTraces(0) {}

    when:
    message.removeProperty(TRIGGER_PAUSE)
    activateSpan(span0).withCloseable {
      AxisEngine.resume(message)
    }

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        testSpan(it, 0)
        axisSpan(it, 1, 'local://services/TestService/testAction', span(0))
        axisSpan(it, 2, 'local://services/TestService/testAction', span(1))
      }
    }
  }

  def "test exception during send"() {
    when:
    def message = testMessage()
    message.setProperty(TRIGGER_FAIL, true)
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      try {
        AxisEngine.send(message)
      } catch (RuntimeException ignore) {
        // expected
      }
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(2) {
        testSpan(it, 0)
        axisSpanWithException(it, 1, 'local://services/TestService/testAction', span(0),
          new RuntimeException('Internal Error'))
      }
    }
  }

  def "test sendFault"() {
    when:
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    def faultMessage = createFaultMessageContext(testMessage(), new Exception('internal error'))
    activateSpan(span0).withCloseable {
      AxisEngine.sendFault(faultMessage)
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(2) {
        testSpan(it, 0)
        axisSpanWithError(it, 1, 'http://www.w3.org/2005/08/addressing/soap/fault', span(0))
      }
    }
  }

  def "test receive"() {
    when:
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      def message1 = testMessage()
      message1.setSoapAction('testAction')
      AxisEngine.receive(message1)

      def message2 = testMessage()
      // no action, expect span to use testDestination
      AxisEngine.receive(message2)
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(3) {
        testSpan(it, 0)
        axisSpan(it, 1, 'local://services/TestService/testAction', span(0))
        axisSpan(it, 2, 'testAction', span(0))
      }
    }
  }

  def "should set resource name on local root"() {
    setup:
    injectSysConfig("trace.axis.promote.resource-name", "true")
    when:
    // emulates AxisServlet behaviour
    AgentSpan span0 = startSpan("servlet.request")
    span0.setServiceName("testSpan")
    span0.setResourceName("POST /some/context/services/TestService")
    activateSpan(span0).withCloseable {
      def message1 = testMessage()
      message1.setSoapAction('testAction')
      message1.setServerSide(true)
      AxisEngine.receive(message1)
      def message2 = testMessage()
      message2.setSoapAction('anotherAction')
      message2.setServerSide(true)
      AxisEngine.receive(message2)
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span {
          parent()
          serviceName "testSpan"
          resourceName "testAction"
          operationName "servlet.request"
        }
        axisSpan(it, 1, 'testAction', span(0))
        axisSpan(it, 2, 'anotherAction', span(0))
      }
    }
  }

  def "test pause+resume receive"() {
    when:
    def message = testMessage()
    message.setProperty(TRIGGER_PAUSE, true)
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      AxisEngine.receive(message)
    }
    span0.finish()

    then:
    assertTraces(0) {}

    when:
    message.removeProperty(TRIGGER_PAUSE)
    activateSpan(span0).withCloseable {
      AxisEngine.resume(message)
    }

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        testSpan(it, 0)
        axisSpan(it, 1, 'local://services/TestService/testAction', span(0))
        axisSpan(it, 2, 'local://services/TestService/testAction', span(1))
      }
    }
  }

  def "test exception during receive"() {
    when:
    def message = testMessage()
    message.setProperty(TRIGGER_FAIL, true)
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      try {
        AxisEngine.receive(message)
      } catch (RuntimeException ignore) {
        // expected
      }
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(2) {
        testSpan(it, 0)
        axisSpanWithException(it, 1, 'local://services/TestService/testAction', span(0),
          new RuntimeException('Internal Error'))
      }
    }
  }

  def testMessage() {
    def message = serverContext.createMessageContext()

    // create a simple message for our test service
    message.setTo(new EndpointReference('local://services/TestService/testAction'))
    message.setFaultTo(new EndpointReference('local://services/TestService/testFault'))
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
}
