package salistener

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jms.listener.DefaultMessageListenerContainer
import org.springframework.jms.listener.MessageListenerContainer

import javax.jms.ConnectionFactory

@Configuration
class MaxMessagesPerTaskConfig extends Config {

  @Bean
  MessageListenerContainer container(ConnectionFactory connectionFactory) {
    def container = super.container(connectionFactory)
    ((DefaultMessageListenerContainer) container).setMaxMessagesPerTask(1) // only call 'receive' once per task
    return container
  }
}
