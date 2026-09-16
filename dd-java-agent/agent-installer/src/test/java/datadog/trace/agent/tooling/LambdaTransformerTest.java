package datadog.trace.agent.tooling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.application.LambdaTarget;
import datadog.trace.bootstrap.instrumentation.java.lang.invoke.LambdaTransformer;
import datadog.trace.bootstrap.instrumentation.java.lang.invoke.LambdaTransformerHolder;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.agent.builder.AgentBuilder;
import org.junit.jupiter.api.Test;

class LambdaTransformerTest {

  @Test
  void disabledInstallationClearsPreviousTransformer() {
    LambdaTransformer previous = (className, targetClass, classBytes, interfaceName) -> classBytes;
    LambdaTransformerHolder.set(previous);
    try {
      AgentInstaller.registerLambdaTransformer(false, null, new String[0]);

      assertNull(LambdaTransformerHolder.get());
    } finally {
      LambdaTransformerHolder.set(null);
    }
  }

  @Test
  void publishesTransformerBeforeInstallationAndClearsItOnError() {
    AgentBuilder.InstallationListener listener =
        AgentInstaller.lambdaInstallationListener(true, new String[] {Runnable.class.getName()});
    Throwable failure = new IllegalStateException("installation failed");
    try {
      listener.onBeforeInstall(null, null);

      assertNotNull(LambdaTransformerHolder.get());
      assertSame(failure, listener.onError(null, null, failure));
      assertNull(LambdaTransformerHolder.get());
    } finally {
      LambdaTransformerHolder.set(null);
    }
  }

  @Test
  void onlyTransformsEnabledInterfaces() {
    byte[] transformedBytes = new byte[0];
    AtomicBoolean transformed = new AtomicBoolean();
    LambdaTransformer delegate =
        (className, targetClass, classBytes, interfaceName) -> {
          transformed.set(true);
          return transformedBytes;
        };
    LambdaTransformer transformer =
        AgentInstaller.filterLambdaTransformer(delegate, new String[] {Runnable.class.getName()});

    assertSame(
        transformedBytes,
        transformer.transform(
            "test/Lambda", LambdaTarget.class, new byte[0], Runnable.class.getName()));
    transformed.set(false);
    assertNull(
        transformer.transform("test/Lambda", LambdaTarget.class, new byte[0], "other.Interface"));
    assertFalse(transformed.get());
  }

  @Test
  void skipsGloballyIgnoredTargets() {
    AtomicBoolean transformed = new AtomicBoolean();
    LambdaTransformer delegate =
        (className, targetClass, classBytes, interfaceName) -> {
          transformed.set(true);
          return classBytes;
        };
    LambdaTransformer transformer =
        AgentInstaller.filterLambdaTransformer(delegate, new String[] {Runnable.class.getName()});

    assertNull(
        transformer.transform(
            "datadog/trace/Lambda",
            LambdaTransformerTest.class,
            new byte[0],
            Runnable.class.getName()));
    assertFalse(transformed.get());
  }
}
