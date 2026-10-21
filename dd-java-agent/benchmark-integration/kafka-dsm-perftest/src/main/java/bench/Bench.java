package bench;

import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import jdk.jfr.Recording;

/** Kafka produce/consume overhead benchmark. Tracer attached via -javaagent by run.sh. */
public class Bench {
  static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }
  static final int COMMIT_EVERY = Integer.parseInt(env("COMMIT_EVERY", "100"));
  static final int WARMUP = Integer.parseInt(env("WARMUP", "5"));
  static final int COUNT = Integer.parseInt(env("COUNT", "25"));
  static final String BOOT = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
  static final String BASE = env("KAFKA_TOPIC", "benchmark-topic-java-default");
  static final String OUT = env("TIMEIT_OUTPUT", "results.json");
  static final boolean PROFILE = "1".equals(env("PROFILE", "0"));
  static final String PROFILE_OUTPUT = env("PROFILE_OUTPUT", "profile.jfr");
  static final int N = 1000;
  static final String RUN = UUID.randomUUID().toString().substring(0, 8);

  static double rssMb() throws Exception {
    return Double.parseDouble(new String(
        new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(ProcessHandle.current().pid()))
            .start().getInputStream().readAllBytes()).trim()) / 1024.0;
  }

  /** Total committed memory (MB) reported by Native Memory Tracking (includes the Java heap). */
  static double nmtCommittedMb() throws Exception {
    Process p = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(),
            String.valueOf(ProcessHandle.current().pid()), "VM.native_memory", "summary", "scale=KB")
        .redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("Total: reserved=\\d+KB, committed=(\\d+)KB").matcher(out);
    if (!m.find()) throw new IllegalStateException("NMT total not found: " + out);
    return Long.parseLong(m.group(1)) / 1024.0;
  }

  /** Heap used (MB) after an explicit GC. */
  static double heapUsedAfterGcMb() {
    System.gc();
    Runtime rt = Runtime.getRuntime();
    return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
  }

  static Recording startRecording() {
    Recording rec = new Recording();
    rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
    rec.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(1));
    rec.enable("jdk.ObjectAllocationSample");
    rec.start();
    return rec;
  }

  static void stopRecording(Recording rec, int timedIteration) throws Exception {
    rec.stop();
    rec.dump(Path.of(PROFILE_OUTPUT + ".iter" + timedIteration));
    rec.close();
  }

  /** Concatenates the per-iteration JFR chunks into a single recording. */
  static void mergeRecordings() throws Exception {
    try (var out = Files.newOutputStream(Path.of(PROFILE_OUTPUT))) {
      for (int i = 0; i < COUNT; i++) {
        Path part = Path.of(PROFILE_OUTPUT + ".iter" + i);
        Files.copy(part, out);
        Files.delete(part);
      }
    }
  }

  static double[] iteration(int i, boolean profile) throws Exception {
    String topic = BASE + "-" + RUN + "-" + i;
    try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", BOOT))) {
      admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
    }
    // setup (untimed)
    Properties pp = new Properties();
    pp.put("bootstrap.servers", BOOT);
    pp.put("key.serializer", ByteArraySerializer.class.getName());
    pp.put("value.serializer", ByteArraySerializer.class.getName());
    Properties cp = new Properties();
    cp.put("bootstrap.servers", BOOT);
    cp.put("group.id", "g-" + RUN + "-" + i);
    cp.put("auto.offset.reset", "earliest");
    cp.put("enable.auto.commit", "false");
    cp.put("key.deserializer", ByteArrayDeserializer.class.getName());
    cp.put("value.deserializer", ByteArrayDeserializer.class.getName());
    byte[] payload = new byte[100];
    Arrays.fill(payload, (byte) 'x');
    double produceMs, consumeMs;
    try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(pp);
         KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(cp)) {
      consumer.subscribe(List.of(topic));
      while (consumer.assignment().isEmpty()) consumer.poll(Duration.ofMillis(100));

      // Profiling (JFR) covers only the timed produce+consume phases of timed iterations.
      Recording jfr = profile ? startRecording() : null;
      long t0 = System.nanoTime();
      for (int k = 0; k < N; k++) {
        ProducerRecord<byte[], byte[]> r = new ProducerRecord<>(topic, null, payload);
        for (int h = 0; h < 5; h++) r.headers().add("h" + h, ("v" + h).getBytes(StandardCharsets.UTF_8));
        producer.send(r);
      }
      producer.flush();
      long t1 = System.nanoTime();
      int got = 0;
      while (got < N) {
        for (ConsumerRecord<byte[], byte[]> rec : consumer.poll(Duration.ofMillis(1000))) {
          got++;
          if (got % COMMIT_EVERY == 0 || got == N) consumer.commitSync();
        }
      }
      long t2 = System.nanoTime();
      if (jfr != null) stopRecording(jfr, i - WARMUP);
      produceMs = (t1 - t0) / 1e6;
      consumeMs = (t2 - t1) / 1e6;
    } // teardown (untimed)
    return new double[] {produceMs, consumeMs};
  }

  public static void main(String[] a) throws Exception {
    for (int i = 0; i < WARMUP; i++) iteration(i, false);
    StringBuilder runs = new StringBuilder();
    double maxRss = rssMb();
    for (int i = 0; i < COUNT; i++) {
      double[] r = iteration(WARMUP + i, PROFILE);
      maxRss = Math.max(maxRss, rssMb());
      System.out.printf("iter %d: produce=%.1fms consume=%.1fms%n", i, r[0], r[1]);
      if (i > 0) runs.append(",");
      runs.append(String.format(Locale.ROOT, "{\"produce_ms\":%f,\"consume_ms\":%f}", r[0], r[1]));
    }
    if (PROFILE) mergeRecordings();
    double heapUsedMb = heapUsedAfterGcMb();
    double nmtMb = nmtCommittedMb();
    double finalRss = rssMb();
    try (FileWriter w = new FileWriter(OUT)) {
      w.write("{\"runs\":[" + runs + "],\"max_rss_mb\":" + maxRss + ",\"final_rss_mb\":" + finalRss
          + ",\"heap_used_after_gc_mb\":" + heapUsedMb + ",\"nmt_committed_mb\":" + nmtMb
          + ",\"commit_every\":" + COMMIT_EVERY + "}");
    }
    System.exit(0);
  }
}
