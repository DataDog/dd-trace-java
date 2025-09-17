package listener

import io.awspring.cloud.sqs.config.SqsBootstrapConfiguration
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory
import org.elasticmq.rest.sqs.SQSRestServer
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient

import jakarta.annotation.PreDestroy

@Configuration
@Import(SqsBootstrapConfiguration)
class Config {

  @Bean
  SQSRestServer sqsRestServer() {
    return SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start()
  }

  @Bean
  SqsMessageListenerContainerFactory<Object> defaultSqsListenerContainerFactory() {
    return SqsMessageListenerContainerFactory
      .builder()
      .sqsAsyncClient(sqsAsyncClient())
      .build()
  }

  @Bean
  SqsAsyncClient sqsAsyncClient() {
    def sqsAddress = sqsRestServer().waitUntilStarted().localAddress()
    return SqsAsyncClient.builder()
      .credentialsProvider(AnonymousCredentialsProvider.create())
      .region(Region.AWS_GLOBAL)
      .endpointOverride(new URI("http://localhost:${sqsAddress.port}"))
      .build()
  }

  @Bean
  TestListener testListener() {
    return new TestListener()
  }

  @PreDestroy
  void destroy() {
    sqsRestServer().stopAndWait()
  }
}
