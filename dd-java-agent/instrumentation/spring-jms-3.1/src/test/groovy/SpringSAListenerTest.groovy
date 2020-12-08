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
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.activemq.ActiveMQMessageConsumer
import org.apache.activemq.junit.EmbeddedActiveMQBroker
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jms.core.JmsTemplate
import org.springframework.jms.listener.MessageListenerContainer
import salistener.Config
import salistener.SATestListener

import javax.jms.ConnectionFactory

class SpringSAListenerTest extends AgentTestRunner {

  def "receiving message in spring session aware listener generates spans"() {
    setup:
    def context = new AnnotationConfigApplicationContext(Config)
    def factory = context.getBean(ConnectionFactory)
    def container = context.getBean(MessageListenerContainer)
    container.start()
    def template = new JmsTemplate(factory)
    template.convertAndSend("SpringSAListenerJMS", "a message")

    expect:
    // The framework continues to poll the queue and will generate additional "jms.consume/JMS receive" traces when the receive times out, so we want to ignore these additional traces.
    assertTraces(3, true) {
      producerTrace(it, "Queue SpringSAListenerJMS")
      consumerTrace(it, "Queue SpringSAListenerJMS", false, ActiveMQMessageConsumer, trace(0)[0])
      consumerTrace(it, "Queue SpringSAListenerJMS", true, SATestListener, trace(0)[0])
    }

    cleanup:
    context.getBean(EmbeddedActiveMQBroker).stop()
  }

  static producerTrace(ListWriterAssert writer, String jmsResourceName) {
    writer.trace(1) {
      span {
        serviceName "jms"
        operationName "jms.produce"
        resourceName "Produced for $jmsResourceName"
        spanType DDSpanTypes.MESSAGE_PRODUCER
        errored false
        parent()

        tags {
          "$Tags.COMPONENT" "jms"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
          defaultTags()
        }
      }
    }
  }

  static consumerTrace(ListWriterAssert writer, String jmsResourceName, boolean messageListener, Class origin, DDSpan parentSpan) {
    writer.trace(1) {
      span {
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
          if (!messageListener && "$InstrumentationTags.RECORD_QUEUE_TIME_MS") {
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" {it >= 0 }
          }
          defaultTags(true)
        }
      }
    }
  }
}
