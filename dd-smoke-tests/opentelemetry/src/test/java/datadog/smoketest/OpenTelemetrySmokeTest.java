package datadog.smoketest;

import static datadog.smoketest.backend.AgentBackend.testAgent;
import static datadog.smoketest.trace.SmokeTraceAssertions.UNORDERED;
import static datadog.smoketest.trace.SpanLinkMatcher.toIndex;
import static datadog.smoketest.trace.SpanMatcher.span;
import static datadog.smoketest.trace.TraceMatcher.trace;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Comparator.comparing;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.smoketest.trace.SpanMatcher;
import datadog.smoketest.trace.TraceMatcher.Options;
import datadog.trace.api.ProtocolVersion;
import datadog.trace.test.agent.decoder.DecodedSpan;
import java.io.File;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.ClassTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterClassTemplateInvocationCallback;
import org.junit.jupiter.api.extension.BeforeClassTemplateInvocationCallback;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContext;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContextProvider;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

/**
 * Runs the OpenTelemetry app once per trace agent protocol: span events and links travel as meta
 * tags with {@code v0.4} and {@code v0.5} but as native span fields with {@code v1.0}, and must
 * decode alike.
 */
@ClassTemplate
@ExtendWith(OpenTelemetrySmokeTest.AppPerProtocol.class)
class OpenTelemetrySmokeTest {
  private static final UnaryOperator<Options> SORT_BY_RESOURCE_NAME =
      options -> options.sort(comparing(DecodedSpan::getResource));
  private static final int TIMEOUT_SECONDS = 30;
  private static final File WORKING_DIRECTORY =
      new File(System.getProperty("datadog.smoketest.builddir"));
  private static final String APPLICATION_JAR =
      System.getProperty("datadog.smoketest.shadowJar.path");

  /** The app of the current protocol, set by {@link AppLifecycle}. */
  private SmokeCliApp app;

  @Test
  void receivesTraces() {
    app.traces()
        .waitForTraces(
            TIMEOUT_SECONDS,
            UNORDERED,
            // The standalone annotated span from the app set-up.
            trace(
                span()
                    .root()
                    .operationName("Application.loadConfiguration")
                    .tag("events", validates(events -> events.contains("configuration-loaded")))),
            // The batch trace:
            //   batch-job                        (tracer, server, root)
            //   ├─ Application.splitBatch        (annotated, main thread)
            //   ├─ shard-0                       (tracer, worker thread)   ┐
            //   ├─ shard-1                       (tracer, worker thread)   ├ fan-out
            //   ├─ shard-2                       (tracer, worker thread)   ┘
            //   └─ batch-merge                   (tracer, links to the shards) ← fan-in
            //      └─ Application.reportResults  (annotated, main thread)
            trace(
                SORT_BY_RESOURCE_NAME,
                // 0: Application.reportResults, child of batch-merge (index 3)
                span()
                    .operationName("Application.reportResults")
                    .childOfIndex(3)
                    .metric("batch.merged-shards", validates(shards -> shards.intValue() == 3)),
                // 1: Application.splitBatch, child of batch-job (index 2)
                span()
                    .operationName("Application.splitBatch")
                    .childOfIndex(2)
                    .metric("batch.shards", validates(shards -> shards.intValue() == 3)),
                // 2: batch-job, the root
                span().root().operationName("server.request").resourceName("batch-job"),
                // 3: batch-merge, fanning the shards (indexes 4 to 6) back in
                span()
                    .operationName("internal")
                    .resourceName("batch-merge")
                    .childOfIndex(2)
                    .links(toIndex(4), toIndex(5), toIndex(6)),
                // 4 to 6: the shards, fanned out over the pool but all children of batch-job
                shard(0),
                shard(1),
                shard(2)));
    app.assertCompletesWithValue(TIMEOUT_SECONDS, SECONDS, 0);
  }

  private static SmokeCliApp app(ProtocolVersion protocol) {
    return SmokeCliApp.named("opentelemetry")
        .jar(APPLICATION_JAR)
        .jvmArgs(
            "-Ddd.trace.otel.enabled=true",
            "-Ddd.trace.agent.protocol.version=" + protocol.asConfigValue())
        .workingDirectory(WORKING_DIRECTORY)
        .backend(testAgent())
        .build();
  }

  private static SpanMatcher shard(int shard) {
    return span()
        .operationName("internal")
        .resourceName("shard-" + shard)
        .childOfIndex(2)
        .metric("batch.shard", validates(value -> value.intValue() == shard));
  }

  /**
   * Runs the class once per {@link ProtocolVersion}, each invocation with its own app. Class-level
   * callbacks such as {@code BeforeAllCallback} run once per class template, not per invocation, so
   * {@link AppLifecycle} launches and stops the app around its invocation instead.
   */
  static final class AppPerProtocol implements ClassTemplateInvocationContextProvider {
    @Override
    public boolean supportsClassTemplate(ExtensionContext context) {
      return true;
    }

    @Override
    public Stream<ClassTemplateInvocationContext> provideClassTemplateInvocationContexts(
        ExtensionContext context) {
      return stream(ProtocolVersion.values()).map(AppPerProtocol::invocation);
    }

    private static ClassTemplateInvocationContext invocation(ProtocolVersion protocol) {
      SmokeCliApp app = app(protocol);
      return new ClassTemplateInvocationContext() {
        @Override
        public String getDisplayName(int invocationIndex) {
          return "protocol " + protocol.asConfigValue();
        }

        @Override
        public List<Extension> getAdditionalExtensions() {
          // The app itself for its per-test-method callbacks.
          return asList(app, new AppLifecycle(app));
        }
      };
    }
  }

  /**
   * Launches and stops an app around a class template invocation, and sets it on each test instance
   * of that invocation.
   */
  static final class AppLifecycle
      implements BeforeClassTemplateInvocationCallback,
          AfterClassTemplateInvocationCallback,
          TestInstancePostProcessor {
    private final SmokeCliApp app;

    AppLifecycle(SmokeCliApp app) {
      this.app = app;
    }

    @Override
    public void beforeClassTemplateInvocation(ExtensionContext context) throws Exception {
      this.app.beforeAll(context);
    }

    @Override
    public void afterClassTemplateInvocation(ExtensionContext context) {
      this.app.afterAll(context);
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
      ((OpenTelemetrySmokeTest) testInstance).app = this.app;
    }
  }
}
