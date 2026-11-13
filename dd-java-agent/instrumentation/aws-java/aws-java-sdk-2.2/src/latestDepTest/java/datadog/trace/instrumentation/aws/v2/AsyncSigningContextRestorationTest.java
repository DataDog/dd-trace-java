package datadog.trace.instrumentation.aws.v2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextHandle;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.signer.AsyncSigner;
import software.amazon.awssdk.core.signer.Signer;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
class AsyncSigningContextRestorationTest extends AbstractInstrumentationTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void signingAndHttpFailureRestoreTheSubmittingThread(boolean delayedSigning) throws Exception {
    DelayedSigner signer = new DelayedSigner(delayedSigning);
    AtomicReference<Context> submissionContext = new AtomicReference<>();
    SdkAsyncHttpClient httpClient =
        new SdkAsyncHttpClient() {
          @Override
          public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
            submissionContext.set(Context.current());
            RuntimeException failure = new IllegalStateException("HTTP transport failed");
            request.responseHandler().onError(failure);
            CompletableFuture<Void> result = new CompletableFuture<>();
            result.completeExceptionally(failure);
            return result;
          }

          @Override
          public void close() {}
        };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AgentSpan parent = tracer.startSpan("test", "parent");
    ContextHandle original = parent.swap();
    try (S3AsyncClient client =
        S3AsyncClient.builder()
            .httpClient(httpClient)
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create("http://localhost"))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
            .overrideConfiguration(c ->
                c.retryPolicy(RetryPolicy.none())
                    .putAdvancedOption(SdkAdvancedClientOption.SIGNER, signer))
            .build()) {
      CompletableFuture<?> operation =
          client.headObject(HeadObjectRequest.builder().bucket("bucket").key("key").build());
      if (delayedSigning) {
        assertFalse(operation.isDone(), "request must wait for the asynchronous signer");
        AtomicReference<Context> before = new AtomicReference<>();
        AtomicReference<Context> after = new AtomicReference<>();
        executor
            .submit(() -> {
              before.set(Context.current());
              signer.signed.complete(signer.request);
              after.set(Context.current());
            })
            .get(10, TimeUnit.SECONDS);
        assertSame(
            before.get(), after.get(), "the signing thread must regain its original context");
      }
      assertThrows(ExecutionException.class, () -> operation.get(10, TimeUnit.SECONDS));
      assertNotNull(submissionContext.get(), "request must reach the HTTP transport");
      assertSame(
          AgentTracer.noopSpan(),
          submissionContext.get(),
          "HTTP submission must suppress Netty scope capture");
      assertSame(parent, Context.current());
    } finally {
      original.swap();
      parent.finish();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  private static final class DelayedSigner implements Signer, AsyncSigner {
    final CompletableFuture<SdkHttpFullRequest> signed = new CompletableFuture<>();
    final boolean delayed;
    SdkHttpFullRequest request;

    DelayedSigner(boolean delayed) {
      this.delayed = delayed;
    }

    @Override
    public SdkHttpFullRequest sign(SdkHttpFullRequest request, ExecutionAttributes attributes) {
      throw new AssertionError("SDK must use the asynchronous signer");
    }

    @Override
    public CompletableFuture<SdkHttpFullRequest> sign(
        SdkHttpFullRequest request, AsyncRequestBody body, ExecutionAttributes attributes) {
      this.request = request;
      return delayed ? signed : CompletableFuture.completedFuture(request);
    }
  }
}
