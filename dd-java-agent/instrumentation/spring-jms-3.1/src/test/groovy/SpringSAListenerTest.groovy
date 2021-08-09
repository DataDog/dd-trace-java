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
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.activemq.junit.EmbeddedActiveMQBroker
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jms.core.JmsTemplate
import org.springframework.jms.listener.MessageListenerContainer
import salistener.Config

import javax.jms.ConnectionFactory

class SpringSAListenerTest extends AgentTestRunner {

  static String expectedServiceName() {
    datadog.trace.api.Config.get().isJmsLegacyTracingEnabled()
      ? 'jms' : CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
  }

  def "receiving message in spring session aware listener generates spans"() {
    setup:
    def context = new AnnotationConfigApplicationContext(Config)
    def factory = context.getBean(ConnectionFactory)
    def container = context.getBean(MessageListenerContainer)
    container.start()
    def template = new JmsTemplate(factory)
    template.convertAndSend("SpringSAListenerJMS", "a message")

    expect:
    assertTraces(2) {
      producerTrace(it, "Queue SpringSAListenerJMS")
      consumerTrace(it, "Queue SpringSAListenerJMS", trace(0)[0])
    }

    cleanup:
    context.getBean(EmbeddedActiveMQBroker).stop()
  }

  static producerTrace(ListWriterAssert writer, String jmsResourceName) {
    writer.trace(1) {
      span {
        serviceName expectedServiceName()
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

  static consumerTrace(ListWriterAssert writer, String jmsResourceName, DDSpan parentSpan) {
    writer.trace(2) {
      span {
        operationName "jms.deliver"
        resourceName "$jmsResourceName"
        spanType DDSpanTypes.MESSAGE_BROKER
        errored false
        childOf parentSpan

        tags {
          "$Tags.COMPONENT" "jms"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_BROKER
          defaultTags(true)
        }
      }
      span {
        serviceName expectedServiceName()
        operationName "jms.consume"
        resourceName "Consumed from $jmsResourceName"
        spanType DDSpanTypes.MESSAGE_CONSUMER
        errored false
        childOf span(0)

        tags {
          "$Tags.COMPONENT" "jms"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
          if ("$InstrumentationTags.RECORD_QUEUE_TIME_MS") {
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" {it >= 0 }
          }
          defaultTags(false)
        }
      }
    }
  }
}
