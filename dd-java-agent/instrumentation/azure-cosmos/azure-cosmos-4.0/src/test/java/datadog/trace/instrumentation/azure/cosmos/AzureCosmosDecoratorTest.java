package datadog.trace.instrumentation.azure.cosmos;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.azure.cosmos.AzureCosmosDecorator.DECORATE;
import static datadog.trace.instrumentation.azure.cosmos.AzureCosmosDecorator.STATUS_CODE;
import static datadog.trace.instrumentation.azure.cosmos.AzureCosmosDecorator.SUB_STATUS_CODE;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.cosmos.CosmosException;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * Documents which Cosmos failures mark the request span as an error.
 *
 * <p>Each span covers one request, under the SDK's retry policy, so an attempt the SDK retries gets
 * its own span. Throttling (429), retry-with (449) and partition key range gone (410 with
 * sub-status 1002) are retried by the SDK, and a 404 is often an expected result: their spans keep
 * the status tags but aren't errors. A request that keeps getting a 429 until the SDK gives up is
 * therefore not an error on any Cosmos span. Every other failure is an error.
 *
 * <p>The tests use plain asserts: a lambda capturing the span would put a bootstrap class in a
 * method signature.
 */
class AzureCosmosDecoratorTest extends AbstractInstrumentationTest {

  @TableTest({
    "Scenario                          | status | subStatus | error",
    "throttled, retried by the SDK     | 429    | 3200      | false",
    "throttled without a sub-status    | 429    | 0         | false",
    "retry-with, retried by the SDK    | 449    | 0         | false",
    "partition key range gone, retried | 410    | 1002      | false",
    "item not found                    | 404    | 0         | false",
    "gone without a retried sub-status | 410    | 0         | true ",
    "gone with another sub-status      | 410    | 1000      | true ",
    "conflict                          | 409    | 0         | true ",
    "bad request                       | 400    | 0         | true ",
    "forbidden                         | 403    | 0         | true ",
    "service unavailable               | 503    | 0         | true "
  })
  void tagsCosmosFailures(int status, int subStatus, boolean error) {
    AgentSpan span = startSpan("azure-cosmos", "cosmosdb.query");
    DECORATE.onError(span, new TestCosmosException(status, subStatus));
    span.finish();

    assertEquals(error, span.isError(), "error flag");
    assertEquals(Integer.toString(status), span.getTag(STATUS_CODE));
    assertEquals(subStatus == 0 ? null : Integer.toString(subStatus), span.getTag(SUB_STATUS_CODE));
    if (error) {
      assertEquals(TestCosmosException.class.getName(), span.getTag(DDTags.ERROR_TYPE));
      assertNotNull(span.getTag(DDTags.ERROR_MSG));
    } else {
      assertNull(span.getTag(DDTags.ERROR_TYPE));
      assertNull(span.getTag(DDTags.ERROR_MSG));
    }
  }

  @Test
  void otherFailuresAreErrorsWithoutStatusTags() {
    AgentSpan span = startSpan("azure-cosmos", "cosmosdb.query");
    DECORATE.onError(span, new IllegalStateException("not a Cosmos failure"));
    span.finish();

    assertTrue(span.isError(), "error flag");
    assertEquals(IllegalStateException.class.getName(), span.getTag(DDTags.ERROR_TYPE));
    assertNull(span.getTag(STATUS_CODE));
    assertNull(span.getTag(SUB_STATUS_CODE));
  }

  /** {@code CosmosException} constructors are protected. */
  private static final class TestCosmosException extends CosmosException {
    TestCosmosException(int status, int subStatus) {
      super(status, "test", singletonMap("x-ms-substatus", Integer.toString(subStatus)), null);
    }
  }
}
