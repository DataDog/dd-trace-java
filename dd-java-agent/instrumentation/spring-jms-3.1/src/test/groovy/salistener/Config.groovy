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
package salistener

import javax.jms.ConnectionFactory
import org.apache.activemq.junit.EmbeddedActiveMQBroker
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jms.listener.DefaultMessageListenerContainer
import org.springframework.jms.listener.MessageListenerContainer

@Configuration
class Config {

  @Bean
  EmbeddedActiveMQBroker broker() {
    def broker = new EmbeddedActiveMQBroker()
    broker.start()
    return broker
  }

  @Bean
  ConnectionFactory connectionFactory(EmbeddedActiveMQBroker broker) {
    return broker.createConnectionFactory()
  }

  @Bean
  MessageListenerContainer container(ConnectionFactory connectionFactory) {
    def container = new DefaultMessageListenerContainer()
    container.setConnectionFactory(connectionFactory)
    container.setMessageListener(new SATestListener())
    container.setDestinationName("SpringSAListenerJMS")
    return container
  }
}
