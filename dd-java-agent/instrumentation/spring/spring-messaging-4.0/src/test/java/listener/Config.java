package listener;

import io.awspring.cloud.sqs.config.SqsBootstrapConfiguration;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import org.elasticmq.rest.sqs.SQSRestServer;
import org.elasticmq.rest.sqs.SQSRestServerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

@Configuration
@Import(SqsBootstrapConfiguration.class)
public class Config {

  // @Configuration proxies bean methods (proxyBeanMethods = true by default), so repeated calls to
  // sqsRestServer() return the same singleton bean.
  @Bean
  public SQSRestServer sqsRestServer() {
    return SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start();
  }

  @Bean
  public SqsMessageListenerContainerFactory<Object> defaultSqsListenerContainerFactory() {
    return SqsMessageListenerContainerFactory.builder().sqsAsyncClient(sqsAsyncClient()).build();
  }

  @Bean
  public SqsAsyncClient sqsAsyncClient() {
    InetSocketAddress sqsAddress = sqsRestServer().waitUntilStarted().localAddress();
    try {
      return SqsAsyncClient.builder()
          .credentialsProvider(AnonymousCredentialsProvider.create())
          .region(Region.AWS_GLOBAL)
          .endpointOverride(new URI("http://localhost:" + sqsAddress.getPort()))
          .build();
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  @Bean
  public TestListener testListener() {
    return new TestListener();
  }

  @PreDestroy
  public void destroy() {
    sqsRestServer().stopAndWait();
  }
}
