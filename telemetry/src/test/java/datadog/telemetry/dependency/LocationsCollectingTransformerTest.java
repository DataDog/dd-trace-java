package datadog.telemetry.dependency;

import static datadog.telemetry.dependency.DependencyTestHelper.getJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class LocationsCollectingTransformerTest {

  private final DependencyService depService = new DependencyService();
  private final LocationsCollectingTransformer transformer =
      new LocationsCollectingTransformer(depService);

  @Test
  void noDependencyIfNullProtectionDomain() {
    transformer.transform(null, null, null, null, null);

    depService.resolveOneDependency();
    Collection<Dependency> dependencies = depService.drainDeterminedDependencies();

    assertTrue(dependencies.isEmpty());
  }

  @Test
  void noDependencyIfNullCodeSource() {
    ProtectionDomain domain = new ProtectionDomain(null, null);
    transformer.transform(null, null, null, domain, null);

    depService.resolveOneDependency();
    Collection<Dependency> dependencies = depService.drainDeterminedDependencies();

    assertTrue(dependencies.isEmpty());
  }

  @Test
  void noDependencyIfNullLocation() {
    CodeSource source = new CodeSource(null, (Certificate[]) null);
    ProtectionDomain domain = new ProtectionDomain(source, null);
    transformer.transform(null, null, null, domain, null);

    depService.resolveOneDependency();
    Collection<Dependency> dependencies = depService.drainDeterminedDependencies();

    assertTrue(dependencies.isEmpty());
  }

  @Test
  void oneDependencyIfNormalUrl() throws IOException {
    CodeSource source =
        new CodeSource(getJar("bson-4.2.0.jar").toURI().toURL(), (Certificate[]) null);
    ProtectionDomain domain = new ProtectionDomain(source, null);
    transformer.transform(null, null, null, domain, null);

    depService.resolveOneDependency();
    Collection<Dependency> dependencies = depService.drainDeterminedDependencies();

    assertEquals(1, dependencies.size());
  }

  @Test
  void singleDependencyIfRepeatedProtectionDomain() throws IOException {
    CodeSource source =
        new CodeSource(getJar("bson-4.2.0.jar").toURI().toURL(), (Certificate[]) null);
    ProtectionDomain domain = new ProtectionDomain(source, null);
    transformer.transform(null, null, null, domain, null);
    transformer.transform(null, null, null, domain, null);

    depService.resolveOneDependency();
    Collection<Dependency> dependencies = depService.drainDeterminedDependencies();

    assertEquals(1, dependencies.size());
  }

  @Test
  void multipleDependencies() throws MalformedURLException {
    int domainCount = 1000;
    List<ProtectionDomain> domains = new ArrayList<>();
    for (int i = 1; i <= domainCount; i++) {
      CodeSource source =
          new CodeSource(new URL("file:///bson-" + i + ".jar"), (Certificate[]) null);
      domains.add(new ProtectionDomain(source, null));
    }

    DependencyService mockDepService = mock(DependencyService.class);
    LocationsCollectingTransformer mockTransformer =
        new LocationsCollectingTransformer(mockDepService);

    for (ProtectionDomain domain : domains) {
      mockTransformer.transform(null, null, null, domain, null);
    }

    verify(mockDepService, times(domainCount)).addURL(any(URL.class));
    verifyNoMoreInteractions(mockDepService);
  }

  @Test
  @Timeout(10)
  void multipleDependenciesWithConcurrency() throws Exception {
    int threads = 16;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(threads);
    try {
      int domainCount = 3000;
      BlockingQueue<ProtectionDomain> domains = new ArrayBlockingQueue<>(domainCount);
      for (int i = 1; i <= domainCount; i++) {
        CodeSource source =
            new CodeSource(new URL("file:///bson-" + i + ".jar"), (Certificate[]) null);
        domains.add(new ProtectionDomain(source, null));
      }

      DependencyService mockDepService = mock(DependencyService.class);
      LocationsCollectingTransformer mockTransformer =
          new LocationsCollectingTransformer(mockDepService);

      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            executor.submit(
                () -> {
                  latch.countDown();
                  awaitUninterruptibly(latch);
                  ProtectionDomain domain;
                  while ((domain = domains.poll()) != null) {
                    mockTransformer.transform(null, null, null, domain, null);
                  }
                }));
      }
      for (Future<?> future : futures) {
        future.get();
      }

      verify(mockDepService, times(domainCount)).addURL(any(URL.class));
      verifyNoMoreInteractions(mockDepService);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @Timeout(10)
  void singleDependenciesWithConcurrency() throws Exception {
    int threads = 16;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(threads);
    try {
      int iterationsPerThread = 3000;
      CodeSource source = new CodeSource(new URL("file:///bson-1.jar"), (Certificate[]) null);
      ProtectionDomain domain = new ProtectionDomain(source, null);

      DependencyService mockDepService = mock(DependencyService.class);
      LocationsCollectingTransformer mockTransformer =
          new LocationsCollectingTransformer(mockDepService);

      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            executor.submit(
                () -> {
                  latch.countDown();
                  awaitUninterruptibly(latch);
                  for (int j = 0; j < iterationsPerThread; j++) {
                    mockTransformer.transform(null, null, null, domain, null);
                  }
                }));
      }
      for (Future<?> future : futures) {
        future.get();
      }

      // the shared protection domain is cached, so addURL should be called only once, but the
      // cache population race allows up to one call per racing thread
      verify(mockDepService, atLeastOnce()).addURL(any(URL.class));
      verify(mockDepService, atMost(threads)).addURL(any(URL.class));
      verifyNoMoreInteractions(mockDepService);
    } finally {
      executor.shutdownNow();
    }
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
