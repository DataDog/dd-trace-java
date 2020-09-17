/*
 * Copyright 2020, OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.activemq.ActiveMQMessageProducer

import javax.jms.ConnectionFactory
import org.apache.activemq.ActiveMQMessageConsumer
import org.apache.activemq.junit.EmbeddedActiveMQBroker
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jms.core.JmsTemplate
import org.springframework.jms.listener.MessageListenerContainer
import salistener.Config
import salistener.SATestListener

class SpringSAListenerTest extends AgentTestRunner {

  def "receiving message in spring session aware listener generates spans"() {
    setup:
    def context = new AnnotationConfigApplicationContext(Config)
    def factory = context.getBean(ConnectionFactory)
    def container = context.getBean(MessageListenerContainer)
    container.start()
    def template = new JmsTemplate(factory)
    template.convertAndSend("SpringSAListenerJMS", "a message")


    TEST_WRITER.waitForTraces(3)
    // Manually reorder if reported in the wrong order.
    if (TEST_WRITER[1][0].operationName.toString() == "jms.produce") {
      def producerTrace = TEST_WRITER[1]
      TEST_WRITER[1] = TEST_WRITER[0]
      TEST_WRITER[0] = producerTrace
    }

    expect:
    assertTraces(3) {
      producerTrace(it, 0, "Queue SpringSAListenerJMS")
      consumerTrace(it, 1, "Queue SpringSAListenerJMS", false, ActiveMQMessageConsumer)
      consumerTrace(it, 2, "Queue SpringSAListenerJMS", true, SATestListener)
    }

    cleanup:
    context.getBean(EmbeddedActiveMQBroker).stop()
  }

  static producerTrace(ListWriterAssert writer, int index, String jmsResourceName) {
    writer.trace(index, 1) {
      span(0) {
        serviceName "jms"
        operationName "jms.produce"
        resourceName "Produced for $jmsResourceName"
        spanType DDSpanTypes.MESSAGE_PRODUCER
        errored false
        parent()

        tags {
          "$Tags.COMPONENT" "jms"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
          "span.origin.type" ActiveMQMessageProducer.name
          defaultTags()
        }
      }
    }
  }

  static consumerTrace(ListWriterAssert writer, int index, String jmsResourceName, boolean messageListener, Class origin, DDSpan parentSpan = TEST_WRITER[0][0]) {
    writer.trace(index, 1) {
      span(0) {
        serviceName "jms"
        if (messageListener) {
          operationName "jms.onMessage"
          resourceName "Received from $jmsResourceName"
        } else {
          operationName "jms.consume"
          resourceName "Consumed from $jmsResourceName"
        }
        spanType DDSpanTypes.MESSAGE_CONSUMER
        errored false
        childOf parentSpan

        tags {
          "$Tags.COMPONENT" "jms"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
          "span.origin.type" origin.name
          defaultTags(true)
        }
      }
    }
  }
}
