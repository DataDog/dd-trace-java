package datadog.trace.api.profiling;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.api.flare.TracerFlare;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class ProfilerFlareLoggerTest {

  private ProfilerFlareLogger logger;

  @BeforeEach
  void setUp() throws Exception {
    logger = ProfilerFlareLogger.getInstance();
  }

  @AfterEach
  void tearDown() throws Exception {
    logger.cleanup();
  }

  // Singleton Pattern Tests
  @Test
  void testSingletonConsistency() {
    ProfilerFlareLogger instance1 = ProfilerFlareLogger.getInstance();
    ProfilerFlareLogger instance2 = ProfilerFlareLogger.getInstance();

    assertSame(instance1, instance2);
  }

  @Test
  void testSingletonThreadSafety() throws Exception {
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    List<Future<ProfilerFlareLogger>> futures = new ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      futures.add(executor.submit(ProfilerFlareLogger::getInstance));
    }

    ProfilerFlareLogger firstInstance = futures.get(0).get();
    for (Future<ProfilerFlareLogger> future : futures) {
      assertSame(firstInstance, future.get());
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  void testTracerFlareReporterRegistration() {
    try (MockedStatic<TracerFlare> mockedStatic = mockStatic(TracerFlare.class)) {
      ProfilerFlareLogger loggerInstance = new ProfilerFlareLogger();
      mockedStatic.verify(() -> TracerFlare.addReporter(loggerInstance));
    }
  }

  // Logging Functionality Tests
  @Test
  void testBasicLogging() {
    assertTrue(logger.log("Test message"));
  }

  @Test
  void testLoggingWithArguments() {
    assertTrue(logger.log("Test message with {} and {}", "arg1", 42));
  }

  @Test
  void testLoggingWithException() {
    Exception testException = new RuntimeException("Test exception");
    assertTrue(logger.log("Error occurred: {}", "details", testException));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "Simple message", "Message with special chars: !@#$%^&*()"})
  void testLoggingVariousMessages(String message) {
    assertTrue(logger.log(message));
  }

  @Test
  void testLoggingNullMessage() {
    assertTrue(logger.log(null));
  }

  @Test
  void testLoggingNullArguments() {
    assertTrue(logger.log("Message with null: {}", (Object) null));
  }

  @ParameterizedTest
  @MethodSource("provideLogMessageFormats")
  void testSLF4JFormatting(String format, Object[] args, String expectedSubstring) {
    assertTrue(logger.log(format, args));
    // Additional verification could be added if we can access the logged content
  }

  private static Stream<Arguments> provideLogMessageFormats() {
    return Stream.of(
        Arguments.of("Simple message", new Object[] {}, "Simple message"),
        Arguments.of("Message with {}", new Object[] {"placeholder"}, "placeholder"),
        Arguments.of("Multiple {} and {}", new Object[] {"first", "second"}, "first"),
        Arguments.of("Number: {}", new Object[] {123}, "123"),
        Arguments.of("Boolean: {}", new Object[] {true}, "true"));
  }

  // Capacity Management Tests
  @Test
  void testCapacityLimitEnforcement() throws Exception {
    int capacity = logger.getMaxReportCapacity();

    assertEquals(2 * 1024 * 1024, capacity); // 2MiB
  }

  @Test
  void testLoggingWithinCapacity() {
    for (int i = 0; i < 100; i++) {
      assertTrue(logger.log("Message {}", i));
    }
  }

  @Test
  void testCapacityRejection() {
    // Fill up capacity with large messages
    StringBuilder largeMessage = new StringBuilder();
    for (int i = 0; i < 10000; i++) {
      largeMessage.append("Large message content ");
    }

    int iterations = logger.getMaxReportCapacity() / largeMessage.length();
    String message = largeMessage.toString();

    // We must accept all messages until capacity
    for (int i = 0; i < iterations; i++) {
      assertTrue(logger.log(message));
    }

    // Next message should be rejected
    assertFalse(logger.log(message));
  }

  @Test
  void testCapacityAccuracy() throws Exception {
    String testMessage = "Test message";
    logger.log(testMessage);

    int usedCapacity = logger.getUsedReportCapacity();
    assertTrue(usedCapacity > testMessage.length()); // Should include timestamp and formatting
  }

  // TracerFlare Integration Tests
  @Test
  void testAddReportToFlareWithEmptyLogs() throws Exception {
    ZipOutputStream mockZip = mock(ZipOutputStream.class);

    logger.addReportToFlare(mockZip);

    // Should not add any entries for empty logs
    verify(mockZip, never()).putNextEntry(any());
  }

  @Test
  void testAddReportToFlareWithLogs() throws Exception {
    ZipOutputStream mockZip = mock(ZipOutputStream.class);

    logger.log("Test message 1");
    logger.log("Test message 2");

    try (MockedStatic<TracerFlare> mockedStatic = mockStatic(TracerFlare.class)) {
      logger.addReportToFlare(mockZip);
      mockedStatic.verify(
          () -> TracerFlare.addText(eq(mockZip), eq("profiler_log.txt"), anyString()));
    }
  }

  @Test
  void testAddReportToFlareIOException() throws Exception {
    ZipOutputStream mockZip = mock(ZipOutputStream.class);
    logger.log("Test message");

    try (MockedStatic<TracerFlare> mockedStatic = mockStatic(TracerFlare.class)) {
      mockedStatic
          .when(() -> TracerFlare.addText(any(), any(), any()))
          .thenThrow(new IOException("Test IO exception"));

      assertThrows(IOException.class, () -> logger.addReportToFlare(mockZip));
    }
  }

  @Test
  void testFlareContentFormat() throws Exception {
    logger.log("Message 1");
    logger.log("Message 2 with {}", "arg");

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ZipOutputStream zip = new ZipOutputStream(baos);

    try (MockedStatic<TracerFlare> mockedStatic = mockStatic(TracerFlare.class)) {
      mockedStatic
          .when(() -> TracerFlare.addText(any(), any(), any()))
          .then(
              invocation -> {
                String content = invocation.getArgument(2);
                assertTrue(content.contains("Message 1"));
                assertTrue(content.contains("Message 2 with arg"));
                assertTrue(content.contains("\n")); // Should have newlines between messages
                return null;
              });

      logger.addReportToFlare(zip);
    }
  }

  // Cleanup Tests
  @Test
  void testCleanup() throws Exception {
    logger.log("Test message 1");
    logger.log("Test message 2");

    logger.cleanup();

    assertEquals(0, logger.linesSize());
    assertEquals(0, logger.getUsedReportCapacity());
  }

  @Test
  void testLoggingAfterCleanup() {
    logger.log("Before cleanup");
    logger.cleanup();

    assertTrue(logger.log("After cleanup"));
  }

  // Thread Safety Tests
  @Test
  void testConcurrentLogging() throws Exception {
    int threadCount = 20;
    int messagesPerThread = 50;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      executor.submit(
          () -> {
            try {
              startLatch.await();
              for (int j = 0; j < messagesPerThread; j++) {
                if (logger.log("Thread {} message {}", threadId, j)) {
                  successCount.incrementAndGet();
                }
              }
            } catch (Exception e) {
              fail("Exception in thread: " + e.getMessage());
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

    // At least some messages should succeed
    assertTrue(successCount.get() > 0);

    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  void testConcurrentCleanupAndLogging() throws Exception {
    int iterations = 100;
    ExecutorService executor = Executors.newFixedThreadPool(3);

    for (int i = 0; i < iterations; i++) {
      CountDownLatch latch = new CountDownLatch(2);

      // Logger thread
      executor.submit(
          () -> {
            logger.log("Concurrent message");
            latch.countDown();
          });

      // Cleanup thread
      executor.submit(
          () -> {
            logger.cleanup();
            latch.countDown();
          });

      assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
  }

  // Performance and Edge Case Tests
  @Test
  void testLargeMessageHandling() {
    StringBuilder largeMessage = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      largeMessage.append("This is a large message with lots of content. ");
    }

    assertTrue(logger.log(largeMessage.toString()));
  }

  @Test
  void testManySmallMessages() {
    for (int i = 0; i < 10000; i++) {
      if (!logger.log("Message {}", i)) {
        // Hit capacity limit, which is expected
        break;
      }
    }
    // Test should complete without throwing exceptions
  }

  @Test
  void testTimestampFormatting() {
    // Log a message and verify it doesn't throw exceptions during timestamp formatting
    assertDoesNotThrow(() -> logger.log("Timestamp test"));
  }

  @Test
  void testExceptionWithNullMessage() {
    Exception testException = new RuntimeException();
    assertTrue(logger.log("Exception: {}", testException));
  }

  @Test
  void testSpecialCharacterHandling() {
    assertTrue(logger.log("Special chars: \n\t\r\\\"'"));
    assertTrue(logger.log("Unicode: \u2603 \u2764 \u1F44D"));
  }

  @Test
  void testEmptyAndWhitespaceMessages() {
    assertTrue(logger.log(""));
    assertTrue(logger.log("   "));
    assertTrue(logger.log("\t\n\r"));
  }

  @Test
  void testMultipleExceptions() {
    Exception cause = new IllegalStateException("Root cause");
    Exception wrapper = new RuntimeException("Wrapper", cause);

    assertTrue(logger.log("Nested exception: {}", wrapper));
  }

  @Test
  void testLogAfterCapacityHit() {
    // Fill to capacity
    StringBuilder largeMessage = new StringBuilder();
    for (int i = 0; i < 50000; i++) {
      largeMessage.append("Large message ");
    }

    String message = largeMessage.toString();
    while (logger.log(message)) {
      // Keep adding until capacity is hit
    }

    // Verify subsequent messages are rejected
    assertFalse(logger.log("Should be rejected: " + message));
    assertFalse(logger.log("Also rejected: " + message));

    // After cleanup, should accept messages again
    logger.cleanup();
    assertTrue(logger.log("After cleanup"));
  }
}
