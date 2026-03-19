import datadog.trace.instrumentation.jms.JMSDecorator
import spock.lang.Specification

import javax.jms.Queue
import javax.jms.Topic

class JMSDecoratorTest extends Specification {

  def "test getDestinationName sanitizes Kafka Connect schema suffixes"() {
    given:
    def decorator = JMSDecorator.CONSUMER_DECORATE

    when:
    def queue = Mock(Queue) {
      getQueueName() >> rawQueueName
    }
    def result = decorator.getDestinationName(queue)

    then:
    result == expectedName

    where:
    rawQueueName                                           | expectedName
    // Customer reported issue: queue name with _messagebody_0 suffix from Kafka Connect IBM MQ connector
    // See Zendesk ticket #2429181
    "trainmgt.dispatch.trnsheet.p30.v1.pub_messagebody_0"  | "trainmgt.dispatch.trnsheet.p30.v1.pub"

    // Normal queue names should pass through unchanged (like customer's working pure Java apps)
    "ee.wo.aei.delmove.cs"                                 | "ee.wo.aei.delmove.cs"
    "myqueue"                                              | "myqueue"
    "my.queue.name"                                        | "my.queue.name"

    // Other Kafka Connect schema-derived suffixes should also be stripped
    "myqueue_messagebody_0"                                | "myqueue"
    "myqueue_text_0"                                       | "myqueue"
    "myqueue_bytes_0"                                      | "myqueue"
    "myqueue_map_0"                                        | "myqueue"
    "myqueue_value_0"                                      | "myqueue"
    "myqueue_MESSAGEBODY_0"                                | "myqueue"  // case insensitive
    "myqueue_MessageBody_0"                                | "myqueue"  // case insensitive

    // Multiple digit indices
    "myqueue_messagebody_10"                               | "myqueue"
    "myqueue_messagebody_123"                              | "myqueue"

    // Names that look similar but shouldn't be stripped
    "myqueue_messagebody"                                  | "myqueue_messagebody"  // no index
    "messagebody_0_queue"                                  | "messagebody_0_queue"  // not at end
    "myqueue_othersuffix_0"                                | "myqueue_othersuffix_0"  // unknown suffix
  }

  def "test getDestinationName with topic sanitizes Kafka Connect schema suffixes"() {
    given:
    def decorator = JMSDecorator.CONSUMER_DECORATE

    when:
    def topic = Mock(Topic) {
      getTopicName() >> rawTopicName
    }
    def result = decorator.getDestinationName(topic)

    then:
    result == expectedName

    where:
    rawTopicName                    | expectedName
    "mytopic"                       | "mytopic"
    "mytopic_messagebody_0"         | "mytopic"
    "mytopic_text_0"                | "mytopic"
  }

  def "test getDestinationName returns null for null queue name"() {
    given:
    def decorator = JMSDecorator.CONSUMER_DECORATE

    when:
    def queue = Mock(Queue) {
      getQueueName() >> null
    }
    def result = decorator.getDestinationName(queue)

    then:
    result == null
  }

  def "test getDestinationName strips URI prefixes from IBM MQ destination names"() {
    given:
    def decorator = JMSDecorator.CONSUMER_DECORATE

    when:
    def queue = Mock(Queue) {
      getQueueName() >> rawQueueName
    }
    def result = decorator.getDestinationName(queue)

    then:
    result == expectedName

    where:
    rawQueueName                        | expectedName
    // Triple slash (no queue manager) - most common IBM MQ URI form
    "queue:///MY.QUEUE"                 | "MY.QUEUE"
    // Double slash
    "queue://MY.QUEUE"                  | "MY.QUEUE"
    // Topic URI prefixes
    "topic:///MY.TOPIC"                 | "MY.TOPIC"
    "topic://MY.TOPIC"                  | "MY.TOPIC"
    // Case insensitive
    "QUEUE:///MY.QUEUE"                 | "MY.QUEUE"
    "Queue:///MY.QUEUE"                 | "MY.QUEUE"
    "TOPIC:///MY.TOPIC"                 | "MY.TOPIC"
    // Names without prefix pass through unchanged
    "MY.QUEUE"                          | "MY.QUEUE"
    "simple"                            | "simple"
    // Combined: URI prefix + Kafka Connect suffix both stripped
    "queue:///myqueue_messagebody_0"    | "myqueue"
  }

  def "test getDestinationName strips URI prefixes from topic destinations"() {
    given:
    def decorator = JMSDecorator.CONSUMER_DECORATE

    when:
    def topic = Mock(Topic) {
      getTopicName() >> rawTopicName
    }
    def result = decorator.getDestinationName(topic)

    then:
    result == expectedName

    where:
    rawTopicName                        | expectedName
    "topic:///MY.TOPIC"                 | "MY.TOPIC"
    "topic://MY.TOPIC"                  | "MY.TOPIC"
    "queue:///MY.TOPIC"                 | "MY.TOPIC"
  }

  def "test getDestinationName returns null for TIBCO temp prefix"() {
    given:
    def decorator = JMSDecorator.CONSUMER_DECORATE

    when:
    def queue = Mock(Queue) {
      getQueueName() >> '$TMP$myqueue'
    }
    def result = decorator.getDestinationName(queue)

    then:
    result == null
  }
}
