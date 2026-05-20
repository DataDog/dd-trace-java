package listener;

import io.awspring.cloud.sqs.config.SqsBootstrapConfiguration;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode;
import jakarta.annotation.PreDestroy;
import java.net.URI;
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
public class ErrorHandlerConfig {

  @Bean
  public SQSRestServer sqsRestServer() {
    return SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start();
  }

  @Bean
  public SqsAsyncClient sqsAsyncClient() {
    int port = sqsRestServer().waitUntilStarted().localAddress().getPort();
    return SqsAsyncClient.builder()
        .credentialsProvider(AnonymousCredentialsProvider.create())
        .region(Region.AWS_GLOBAL)
        .endpointOverride(URI.create("http://localhost:" + port))
        .build();
  }

  @Bean
  public ErrorHandlerObservation errorHandlerObservation() {
    return new ErrorHandlerObservation();
  }

  @Bean
  public ErrorHandlerListener errorHandlerListener() {
    return new ErrorHandlerListener();
  }

  @Bean
  public ObservingErrorHandler observingErrorHandler(ErrorHandlerObservation observation) {
    return new ObservingErrorHandler(observation);
  }

  @Bean
  public ObservingAsyncErrorHandler observingAsyncErrorHandler(
      ErrorHandlerObservation observation) {
    return new ObservingAsyncErrorHandler(observation);
  }

  @Bean
  public SqsMessageListenerContainerFactory<String> sqsListenerContainerFactory(
      SqsAsyncClient sqsAsyncClient, ObservingErrorHandler errorHandler) {
    return SqsMessageListenerContainerFactory.<String>builder()
        .sqsAsyncClient(sqsAsyncClient)
        .configure(options -> options.acknowledgementMode(AcknowledgementMode.ALWAYS))
        .errorHandler(errorHandler)
        .build();
  }

  @Bean
  public SqsMessageListenerContainerFactory<String> sqsAsyncListenerContainerFactory(
      SqsAsyncClient sqsAsyncClient, ObservingAsyncErrorHandler errorHandler) {
    return SqsMessageListenerContainerFactory.<String>builder()
        .sqsAsyncClient(sqsAsyncClient)
        .configure(options -> options.acknowledgementMode(AcknowledgementMode.ALWAYS))
        .errorHandler(errorHandler)
        .build();
  }

  @PreDestroy
  public void destroy() {
    sqsRestServer().stopAndWait();
  }
}
