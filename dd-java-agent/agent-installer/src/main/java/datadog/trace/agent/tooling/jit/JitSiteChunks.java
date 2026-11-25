package datadog.trace.agent.tooling.jit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PROTOTYPE: hands out per-call-site slots in chunks of trampolines ({@code JitSites0000} and
 * copies of it).
 *
 * <p>Chunk 0 ships with the agent. When a chunk is three quarters used, the next one is stamped on
 * a background thread: a copy of the template's class file with only its name changed (the new name
 * has the same length, so that's an in-place byte patch), then defined next to the template.
 * Allocation never waits: a site that finds no defined slot gets {@code -1} and uses the shared,
 * unslotted helper instead.
 */
final class JitSiteChunks {
  static final String TEMPLATE = "datadog/trace/bootstrap/jit/JitSites0000";
  static final int SLOTS = 64;
  static final int MAX_CHUNKS = 16;
  private static final int PREFETCH_AT = SLOTS * 3 / 4;

  /** Defines a stamped chunk class (binary name with dots) where the template lives. */
  interface Definer {
    void define(String binaryName, byte[] bytecode) throws Throwable;
  }

  private final Definer definer;
  private final AtomicInteger next = new AtomicInteger();
  private final AtomicInteger wanted = new AtomicInteger(1);
  private final AtomicBoolean stamping = new AtomicBoolean();
  private volatile int defined = 1; // chunk 0 ships with the agent
  private volatile boolean failed;
  private byte[] template; // only touched by the stamping thread

  JitSiteChunks(Definer definer) {
    this.definer = definer;
  }

  /** The internal name of the class holding a slot. */
  static String chunkName(int slot) {
    return nameOf(slot / SLOTS);
  }

  static String nameOf(int chunk) {
    String digits = Integer.toString(chunk);
    return TEMPLATE.substring(0, TEMPLATE.length() - digits.length()) + digits;
  }

  /** A global slot number, or {@code -1} if no defined slot is available right now. */
  int allocate() {
    int slot = next.getAndIncrement();
    if (slot < 0 || slot >= MAX_CHUNKS * SLOTS) {
      next.set(MAX_CHUNKS * SLOTS); // stay at the cap
      return -1;
    }
    int chunk = slot / SLOTS;
    if (slot % SLOTS == PREFETCH_AT) {
      request(chunk + 1);
    }
    if (chunk < defined) {
      return slot;
    }
    request(chunk); // slots in a chunk that isn't defined yet are skipped, not waited for
    return -1;
  }

  int definedChunks() {
    return defined;
  }

  private void request(int chunk) {
    if (chunk >= MAX_CHUNKS || failed) {
      return;
    }
    int current;
    while ((current = wanted.get()) <= chunk) {
      if (wanted.compareAndSet(current, chunk + 1)) {
        break;
      }
    }
    if (stamping.compareAndSet(false, true)) {
      Thread thread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  stampWanted();
                }
              },
              "dd-jit-site-chunks");
      thread.setDaemon(true);
      thread.start();
    }
  }

  private void stampWanted() {
    do {
      try {
        while (!failed && defined < wanted.get()) {
          int chunk = defined;
          definer.define(nameOf(chunk).replace('/', '.'), stamp(chunk));
          defined = chunk + 1;
        }
      } catch (Throwable e) {
        failed = true; // stop stamping; sites keep using the unslotted helpers
      } finally {
        stamping.set(false);
      }
      // a request may have arrived after the loop's last check
    } while (!failed && defined < wanted.get() && stamping.compareAndSet(false, true));
  }

  /** The template's class file with its name changed to chunk {@code chunk}'s name. */
  byte[] stamp(int chunk) throws IOException {
    if (null == template) {
      template = readTemplate();
    }
    return rename(template, ascii(TEMPLATE), ascii(nameOf(chunk)));
  }

  private static byte[] readTemplate() throws IOException {
    InputStream in = ClassLoader.getSystemResourceAsStream(TEMPLATE + ".class");
    if (null == in) {
      throw new IOException("template not found");
    }
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) > 0) {
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    } finally {
      in.close();
    }
  }

  /**
   * Replaces every occurrence of {@code from} inside UTF-8 constants with {@code to} (same length),
   * leaving the rest of the class file untouched.
   */
  static byte[] rename(byte[] bytecode, byte[] from, byte[] to) {
    if (from.length != to.length) {
      throw new IllegalArgumentException("names must have the same length");
    }
    byte[] result = bytecode.clone();
    int count = ((result[8] & 0xFF) << 8) | (result[9] & 0xFF);
    int offset = 10;
    int renamed = 0;
    for (int i = 1; i < count; i++) {
      int tag = result[offset];
      if (tag == 1) {
        int length = ((result[offset + 1] & 0xFF) << 8) | (result[offset + 2] & 0xFF);
        // also inside descriptors, e.g. "L<name>;" for the type of `this` in debug info
        for (int at = offset + 3; at + from.length <= offset + 3 + length; at++) {
          if (regionEquals(result, at, from)) {
            System.arraycopy(to, 0, result, at, to.length);
            renamed++;
            at += from.length - 1;
          }
        }
        offset += 3 + length;
      } else {
        int size = StringCallSiteRewriter.constantSize(tag);
        if (size < 0) {
          throw new IllegalArgumentException("unknown constant-pool tag " + tag);
        }
        offset += size;
        if (tag == 5 || tag == 6) {
          i++;
        }
      }
    }
    if (renamed == 0) {
      throw new IllegalArgumentException("template name not found");
    }
    return result;
  }

  private static boolean regionEquals(byte[] b, int offset, byte[] value) {
    for (int i = 0; i < value.length; i++) {
      if (b[offset + i] != value[i]) {
        return false;
      }
    }
    return true;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
