/**
 * Kafka produce/consume workload for the Java DSM overhead benchmark, run by run.sh (see README.md)
 * and by the `java/data-streams-monitoring` benchmarking-platform branch.
 *
 * <p>Java counterpart of dd-trace-py's `benchmarks/kafka_dsm` workload (the setup itself is ported
 * from .NET's `dd-trace-dotnet/data-streams-monitoring`).
 *
 * <p>Unlike the .NET harness (which relaunches the process per iteration under TimeItSharp), this
 * runs all warmup + timed iterations in a single JVM process with the tracer already initialized
 * via -javaagent, mirroring the Python harness's in-process model. Tracer bootstrap/shutdown cost
 * is therefore excluded from the measured iterations here, same as Python.
 *
 * <p>Each iteration uses a fresh single-partition topic and a fresh consumer group, named after
 * KAFKA_TOPIC, a per-process random run id and the iteration. Every message has no key, a fixed
 * 1024-byte payload and 5 headers key0..key4 -> value0..value4.
 *
 * <p>The measured duration (process.internal_duration_ms) is the produce+consume time of each
 * iteration only: produce loop + flush, then consume + a commitSync every COMMIT_EVERY consumed
 * messages (default 100) and after the last one, each committing the last consumed record's offset
 * + 1 (one OffsetCommit broker round trip per commit; MESSAGE_COUNT is 1000, so the default makes
 * 10 commits per iteration). COMMIT_EVERY=1 is the per-message commit diagnostic. Everything else
 * in an iteration is setup/teardown and is NOT timed: topic creation, producer/consumer
 * construction, the producer metadata fetch, the consumer-group join and partition assignment (done
 * before producing), and client close. Those steps swing 30-40 ms between runs, several times DSM's
 * cost, so they used to drown it. The whole-iteration wall time is still reported as
 * process.iteration_wall_ms.median, for context only.
 *
 * <p>Warmup: the tracer instruments the Kafka clients, so C2 keeps compiling well past 5 iterations
 * and that background JIT time landed inside the timed window. WARMUP (default 100) discarded
 * iterations run before the timed ones, the same fixed number for every scenario.
 *
 * <p>JIT/GC metrics: JIT/GC time per timed iteration (incl. setup/teardown). The
 * CompilationMXBean.getTotalCompilationTime() delta and the summed GarbageCollectorMXBean
 * collection-time delta are taken around the whole runBenchmark call of each timed iteration, so
 * they include its untimed setup/teardown, and JIT time is summed across compiler threads; the JIT
 * share (JIT total / timed produce+consume total) can therefore exceed 100%. Heap used
 * (MemoryMXBean) is sampled after each timed iteration. These show whether the timed iterations
 * were really steady state. The heap size is fixed by run.sh (-Xms/-Xmx JAVA_HEAP, default 1g) so
 * RSS does not move in heap-expansion steps.
 *
 * <p>Emits a JSON artifact in the shape the benchmarking-platform's steps/compare-results.py
 * expects:
 *
 * <pre>{@code
 * [
 *   {
 *     "median": <median duration ms>,
 *     "metrics": {
 *       "process.internal_duration_ms.median": <ms>,
 *       "process.internal_duration_ms.std_err": <ms>,
 *       "process.rss_bytes.median": <bytes>,
 *       "process.iteration_wall_ms.median": <ms>,
 *       "phase.produce_ms.median": <ms>,
 *       "phase.consume_ms.median": <ms>,
 *       "jvm.jit_compile_ms.total": <ms, summed over timed iterations incl. setup/teardown>,
 *       "jvm.jit_compile_ms.median": <ms, per timed iteration incl. setup/teardown>,
 *       "jvm.jit_window_share": <jit total / sum of timed produce+consume ms; can exceed 1>,
 *       "jvm.gc_ms.total": <ms, summed over timed iterations incl. setup/teardown>,
 *       "jvm.heap_used_bytes.median": <bytes, sampled after each timed iteration>,
 *       "warmup.iterations": <discarded warmup iterations run (= WARMUP)>,
 *       "config.commit_every": <COMMIT_EVERY, consumed messages per commitSync>
 *     },
 *     "name": <EXPERIMENT_NAME>,
 *     "warmUpCount": <WARMUP>,
 *     "count": <COUNT>,
 *     "data": [
 *       {
 *         "status": "Passed",
 *         "metrics": {
 *           "process.internal_duration_ms": <ms, produce+consume>,
 *           "process.corrected_duration_ms": <ms, iteration wall time>,
 *           "runtime.dotnet.mem.committed": <bytes, process RSS>
 *         }
 *       },
 *       ... one per timed iteration
 *     ]
 *   }
 * ]
 * }</pre>
 *
 * <p>{@code data} is for the platform's steps/convert-to-common-format.sh: bp-analyzer's
 * TimeItSharp converter reads only per-iteration values from it, under TimeItSharp's metric names
 * (it maps runtime.dotnet.mem.committed to RSS, whatever the runtime), and fails without it.
 *
 * <p>The jvm.jit_* keys are omitted when the JVM cannot report compilation time.
 *
 * <p>Tracing/DSM are controlled purely by environment (-javaagent presence +
 * DD_DATA_STREAMS_ENABLED); this class contains no tracer-specific code, so the DSM-on and DSM-off
 * experiments run the exact same bytecode.
 */
import java.io.IOException;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

public class Workload {

  private static final String BOOTSTRAP_SERVERS =
      System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
  private static final String CONSUMER_GROUP_ID = "benchmark-consumer-group";
  private static final int MESSAGE_COUNT = 1000;
  private static final int NUM_HEADERS = 5;
  private static final String PAYLOAD = "x".repeat(1024);
  // Keeps topics and groups fresh across runs against the same broker.
  private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

  public static void main(String[] args) throws Exception {
    int warmup = parseIntEnv("WARMUP", "100", 0);
    int commitEvery = parseIntEnv("COMMIT_EVERY", "100", 1);
    int count = parseIntEnv("COUNT", "25", 1);
    int threadCount = parseIntEnv("NUM_THREADS", "1", 1);
    String outputPath = System.getenv().getOrDefault("TIMEIT_OUTPUT", "results.json");
    String experimentName = System.getenv().getOrDefault("EXPERIMENT_NAME", "kafka-dsm");

    CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
    boolean jitTimeSupported =
        compilation != null && compilation.isCompilationTimeMonitoringSupported();
    List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

    int runId = 0;

    // Warmup iterations are discarded.
    for (int i = 0; i < warmup; i++) {
      runBenchmark(runId++, commitEvery, threadCount);
    }

    List<Double> durationsMs = new ArrayList<>();
    List<Double> iterationWallMs = new ArrayList<>();
    List<Long> rssBytes = new ArrayList<>();
    List<Double> produceMs = new ArrayList<>();
    List<Double> consumeMs = new ArrayList<>();
    List<Double> jitMs = new ArrayList<>();
    List<Long> heapUsedBytes = new ArrayList<>();
    long gcMsTotal = 0L;

    for (int i = 0; i < count; i++) {
      long jitBefore = jitTimeSupported ? compilation.getTotalCompilationTime() : 0L;
      long gcBefore = totalCollectionTimeMs(collectors);
      long start = System.nanoTime();
      double[] phases = runBenchmark(runId++, commitEvery, threadCount);
      iterationWallMs.add((System.nanoTime() - start) / 1_000_000.0);
      if (jitTimeSupported) {
        jitMs.add((double) (compilation.getTotalCompilationTime() - jitBefore));
      }
      gcMsTotal += totalCollectionTimeMs(collectors) - gcBefore;
      heapUsedBytes.add(memory.getHeapMemoryUsage().getUsed());
      durationsMs.add(phases[0] + phases[1]);
      rssBytes.add(readRssBytes());
      produceMs.add(phases[0]);
      consumeMs.add(phases[1]);
    }

    double durationMedian = median(durationsMs);
    double durationStdErr =
        durationsMs.size() > 1 ? stddev(durationsMs) / Math.sqrt(durationsMs.size()) : 0.0;
    long rssMedian = medianLong(rssBytes);
    double iterationWallMedian = median(iterationWallMs);
    double produceMedian = median(produceMs);
    double consumeMedian = median(consumeMs);
    long heapUsedMedian = medianLong(heapUsedBytes);
    double durationsSumMs = durationsMs.stream().mapToDouble(Double::doubleValue).sum();
    double jitMsTotal = jitMs.stream().mapToDouble(Double::doubleValue).sum();
    double jitMsMedian = jitTimeSupported ? median(jitMs) : 0.0;
    double jitWindowShare = durationsSumMs > 0 ? jitMsTotal / durationsSumMs : 0.0;

    String jitMetrics =
        jitTimeSupported
            ? String.format(
                Locale.ROOT,
                "      \"jvm.jit_compile_ms.total\": %.6f,\n"
                    + "      \"jvm.jit_compile_ms.median\": %.6f,\n"
                    + "      \"jvm.jit_window_share\": %.6f,\n",
                jitMsTotal,
                jitMsMedian,
                jitWindowShare)
            : "";

    StringBuilder data = new StringBuilder();
    for (int i = 0; i < count; i++) {
      data.append(
          String.format(
              Locale.ROOT,
              "      {\n"
                  + "        \"status\": \"Passed\",\n"
                  + "        \"metrics\": {\n"
                  + "          \"process.internal_duration_ms\": %.6f,\n"
                  + "          \"process.corrected_duration_ms\": %.6f,\n"
                  + "          \"runtime.dotnet.mem.committed\": %d\n"
                  + "        }\n"
                  + "      }%s\n",
              durationsMs.get(i),
              iterationWallMs.get(i),
              rssBytes.get(i),
              i < count - 1 ? "," : ""));
    }

    String json =
        String.format(
            Locale.ROOT,
            "[\n"
                + "  {\n"
                + "    \"median\": %.6f,\n"
                + "    \"metrics\": {\n"
                + "      \"process.internal_duration_ms.median\": %.6f,\n"
                + "      \"process.internal_duration_ms.std_err\": %.6f,\n"
                + "      \"process.rss_bytes.median\": %d,\n"
                + "      \"process.iteration_wall_ms.median\": %.6f,\n"
                + "      \"phase.produce_ms.median\": %.6f,\n"
                + "      \"phase.consume_ms.median\": %.6f,\n"
                + "%s"
                + "      \"jvm.gc_ms.total\": %d,\n"
                + "      \"jvm.heap_used_bytes.median\": %d,\n"
                + "      \"warmup.iterations\": %d,\n"
                + "      \"config.commit_every\": %d\n"
                + "    },\n"
                + "    \"name\": \"%s\",\n"
                + "    \"warmUpCount\": %d,\n"
                + "    \"count\": %d,\n"
                + "    \"data\": [\n"
                + "%s"
                + "    ]\n"
                + "  }\n"
                + "]\n",
            durationMedian,
            durationMedian,
            durationStdErr,
            rssMedian,
            iterationWallMedian,
            produceMedian,
            consumeMedian,
            jitMetrics,
            gcMsTotal,
            heapUsedMedian,
            warmup,
            commitEvery,
            experimentName.replace("\\", "\\\\").replace("\"", "\\\""),
            warmup,
            count,
            data);

    Files.write(Paths.get(outputPath), json.getBytes("UTF-8"));

    String jitSummary =
        jitTimeSupported
            ? String.format(
                Locale.ROOT,
                "JIT total %.0f ms (%.2f%% of timed produce+consume)",
                jitMsTotal,
                jitWindowShare * 100)
            : "JIT unsupported";
    System.out.printf(
        Locale.ROOT,
        "Duration (produce+consume) median: %.3f ms (+/- %.3f) | RSS median: %.2f MB | produce"
            + " median: %.3f ms | consume+commit median: %.3f ms | iteration wall median (incl."
            + " setup/teardown, context): %.3f ms | runs: %d (warmup %d) | JIT/GC time per timed"
            + " iteration (incl. setup/teardown): %s, GC total %d ms | heap used median: %.2f MB |"
            + " commit every %d%n",
        durationMedian,
        durationStdErr,
        rssMedian / 1_000_000.0,
        produceMedian,
        consumeMedian,
        iterationWallMedian,
        count,
        warmup,
        jitSummary,
        gcMsTotal,
        heapUsedMedian / 1_000_000.0,
        commitEvery);
  }

  /**
   * Parses env var {@code name}: ASCII decimal digits only, value >= {@code minimum}, otherwise
   * exits with a clear error.
   */
  private static int parseIntEnv(String name, String defaultValue, int minimum) {
    String raw = System.getenv().getOrDefault(name, defaultValue);
    int value = -1;
    if (raw.matches("[0-9]+")) {
      try {
        value = Integer.parseInt(raw);
      } catch (NumberFormatException e) {
        value = -1;
      }
    }
    if (value < minimum) {
      System.err.println(name + " must be an integer >= " + minimum + ", got '" + raw + "'");
      System.exit(2);
    }
    return value;
  }

  /** Accumulated collection time in ms summed over every collector that reports it. */
  private static long totalCollectionTimeMs(List<GarbageCollectorMXBean> collectors) {
    long total = 0L;
    for (GarbageCollectorMXBean gc : collectors) {
      long t = gc.getCollectionTime();
      if (t > 0) {
        total += t;
      }
    }
    return total;
  }

  /** Returns {producePhaseMeanMs, consumePhaseMeanMs} across workers for one full pass. */
  private static double[] runBenchmark(int runId, int commitEvery, int threadCount)
      throws Exception {
    String baseTopic = System.getenv().getOrDefault("KAFKA_TOPIC", "benchmark-topic");

    List<String> topics = new ArrayList<>();
    for (int t = 0; t < threadCount; t++) {
      topics.add(String.format("%s-%s-%d-%d", baseTopic, RUN_ID, runId, t));
    }
    createTopics(topics);

    List<Thread> threads = new ArrayList<>();
    List<double[]> phaseTimes = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Exception> firstError = new AtomicReference<>();

    for (int t = 0; t < topics.size(); t++) {
      final String topic = topics.get(t);
      final String groupId = CONSUMER_GROUP_ID + "-" + topic;
      Thread thread =
          new Thread(
              () -> {
                try {
                  phaseTimes.add(runWorker(topic, groupId, commitEvery));
                } catch (Exception e) {
                  firstError.compareAndSet(null, e);
                }
              });
      threads.add(thread);
      thread.start();
    }
    for (Thread thread : threads) {
      thread.join();
    }
    if (firstError.get() != null) {
      throw firstError.get();
    }

    double sumProduce = 0;
    double sumConsume = 0;
    for (double[] p : phaseTimes) {
      sumProduce += p[0];
      sumConsume += p[1];
    }
    int n = Math.max(phaseTimes.size(), 1);
    return new double[] {sumProduce / n, sumConsume / n};
  }

  /** Returns {producePhaseMs, consumePhaseMs} for one worker's produce+consume pass. */
  private static double[] runWorker(String topic, String groupId, int commitEvery)
      throws Exception {
    Properties producerProps = new Properties();
    producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
    producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
    producerProps.put(ProducerConfig.RETRIES_CONFIG, 1);
    producerProps.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    producerProps.put(
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

    Properties consumerProps = new Properties();
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    consumerProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
    consumerProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);
    consumerProps.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumerProps.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

    try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
      // Untimed: fetch the topic metadata so the first send does not block on it.
      producer.partitionsFor(topic);
      consumer.subscribe(Collections.singletonList(topic));
      joinGroup(consumer, topic);

      List<Header> headers = new ArrayList<>();
      for (int j = 0; j < NUM_HEADERS; j++) {
        headers.add(new RecordHeader("key" + j, ("value" + j).getBytes("UTF-8")));
      }

      // Phase 1: produce all messages.
      long produceStart = System.nanoTime();
      for (int i = 0; i < MESSAGE_COUNT; i++) {
        ProducerRecord<String, String> record =
            new ProducerRecord<>(topic, (Integer) null, (String) null, PAYLOAD, headers);
        producer.send(record);
      }
      producer.flush();
      double produceMs = (System.nanoTime() - produceStart) / 1_000_000.0;

      // Phase 2: consume, commitSync every commitEvery messages and after the last. Explicit
      // offsets: an empty-map commitSync sends no OffsetCommit, skipping DSM commit tracking.
      long consumeStart = System.nanoTime();
      int consumed = 0;
      long pollDeadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while (consumed < MESSAGE_COUNT) {
        if (System.nanoTime() > pollDeadline) {
          throw new IllegalStateException(
              "Failed to consume "
                  + MESSAGE_COUNT
                  + " messages within timeout on topic "
                  + topic
                  + " (got "
                  + consumed
                  + ")");
        }
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
        for (ConsumerRecord<String, String> record : records) {
          consumed++;
          if (consumed % commitEvery == 0 || consumed == MESSAGE_COUNT) {
            consumer.commitSync(
                Collections.singletonMap(
                    new TopicPartition(record.topic(), record.partition()),
                    new OffsetAndMetadata(record.offset() + 1)));
          }
        }
      }
      double consumeMs = (System.nanoTime() - consumeStart) / 1_000_000.0;

      if (consumed != MESSAGE_COUNT) {
        throw new IllegalStateException(
            "Consumed " + consumed + "/" + MESSAGE_COUNT + " messages on topic " + topic);
      }

      return new double[] {produceMs, consumeMs};
    }
  }

  /**
   * Waits for the partition assignment before producing, keeping the group join out of the timed
   * consume phase. The topic is empty; auto.offset.reset=earliest then reads from offset 0.
   */
  private static void joinGroup(KafkaConsumer<String, String> consumer, String topic) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (consumer.assignment().isEmpty()) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("No partition assigned within timeout on topic " + topic);
      }
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(50));
      if (!records.isEmpty()) {
        throw new IllegalStateException("Unexpected records on empty topic " + topic);
      }
    }
  }

  private static void createTopics(List<String> topics) throws Exception {
    Properties props = new Properties();
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
    try (Admin admin = Admin.create(props)) {
      List<NewTopic> newTopics = new ArrayList<>();
      for (String topic : topics) {
        newTopics.add(new NewTopic(topic, 1, (short) 1));
      }
      // Fails on an existing topic: every iteration must start from a fresh one.
      admin.createTopics(newTopics).all().get();
    }
  }

  /** Reads the current process's resident set size in bytes via /proc/self/status (Linux only). */
  private static long readRssBytes() throws IOException {
    List<String> lines = Files.readAllLines(Path.of("/proc/self/status"));
    for (String line : lines) {
      if (line.startsWith("VmRSS:")) {
        String[] parts = line.trim().split("\\s+");
        // Format: "VmRSS:\t<kB value> kB"
        long kb = Long.parseLong(parts[1]);
        return kb * 1024L;
      }
    }
    return 0L;
  }

  private static double median(List<Double> values) {
    List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int n = sorted.size();
    if (n % 2 == 1) {
      return sorted.get(n / 2);
    }
    return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
  }

  private static long medianLong(List<Long> values) {
    List<Long> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int n = sorted.size();
    if (n % 2 == 1) {
      return sorted.get(n / 2);
    }
    return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
  }

  private static double stddev(List<Double> values) {
    double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    double sumSq = 0.0;
    for (double v : values) {
      sumSq += (v - mean) * (v - mean);
    }
    return Math.sqrt(sumSq / (values.size() - 1));
  }
}
