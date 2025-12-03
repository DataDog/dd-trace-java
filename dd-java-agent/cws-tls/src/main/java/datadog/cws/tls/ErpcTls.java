package datadog.cws.tls;

import static datadog.trace.util.AgentThreadFactory.AgentThread.CWS_TLS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import datadog.cws.erpc.Erpc;
import datadog.cws.erpc.Request;
import datadog.environment.SystemProperties;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDTraceId;

/**
 * This class is as a thread local storage.
 *
 * <p>It associates and keep current threadId with spanId/traceId in a memory area that can be read
 * from the CWS eBPF code.
 */
public class ErpcTls implements Tls {
  public static final byte REGISTER_SPAN_TLS_OP = 6;
  public static final long TLS_FORMAT = 0;
  static final int SPAN_ID_SIZE = 8; // 64 bits
  static final int TRACE_ID_SIZE = 16; // 128 bits
  static final long ENTRY_SIZE = SPAN_ID_SIZE + TRACE_ID_SIZE;

  // Thread local storage
  private Pointer tls;
  private long maxThreads;
  private int gettidSyscallId;

  private final ThreadLocal<Integer> threadLocal = new ThreadLocal<>();

  public interface CLibrary extends Library {
    CLibrary Instance = (CLibrary) Native.load("c", CLibrary.class);

    NativeLong syscall(NativeLong number, Object... args);
  }

  static int getGettidSyscallId() {
    String arch = SystemProperties.get("os.arch");
    if (arch.equals("amd64")) {
      return 186; // 186 is the syscall ID for "gettid" on amd64
    } else if (arch.equals("arm64")) {
      return 178; // 78 is the syscall ID for "gettid" on arm64
    }
    return 0;
  }

  static boolean isSupported() {
    int syscallID = getGettidSyscallId();
    if (syscallID == 0) {
      return false;
    }
    try {
      CLibrary.Instance.syscall(new NativeLong(syscallID));
    } catch (UnsatisfiedLinkError error) {
      return false;
    }
    return true;
  }

  private int getTID() {
    Integer thread = threadLocal.get();
    if (thread == null && this.gettidSyscallId > 0) {
      int threadId = CLibrary.Instance.syscall(new NativeLong(this.gettidSyscallId)).intValue();
      threadLocal.set(threadId);
    }
    return thread;
  }

  public ErpcTls(int maxThreads, int refresh) {
    Memory tls = new Memory(maxThreads * ENTRY_SIZE);
    tls.clear();

    this.gettidSyscallId = getGettidSyscallId();

    this.maxThreads = maxThreads;
    this.tls = tls;

    registerTls();

    final Thread thread =
        newAgentThread(
            CWS_TLS,
            new Runnable() {
              @Override
              public void run() {
                try {
                  Thread.sleep(refresh);
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  return;
                }

                while (!Thread.interrupted()) {
                  try {
                    registerTls();
                    Thread.sleep(refresh);
                  } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                  }
                }
              }
            });
    thread.start();
  }

  public Pointer getTlsPointer() {
    return tls;
  }

  private void registerTls() {
    Request request = new Request(REGISTER_SPAN_TLS_OP);
    Pointer pointer = request.getDataPointer();

    pointer.setLong(0, TLS_FORMAT);
    pointer.setLong(Native.LONG_SIZE, maxThreads);
    pointer.setPointer(Native.LONG_SIZE * 2L, tls);

    sendRequest(request);
  }

  public void sendRequest(Request request) {
    Erpc.send(request);
  }

  private long getEntryOffset(int threadId) {
    return threadId % maxThreads * ENTRY_SIZE;
  }

  private long getSpanIdOffset(int threadId) {
    return getEntryOffset(threadId);
  }

  private long getTraceIdOffset(int threadId) {
    return getSpanIdOffset(threadId) + SPAN_ID_SIZE;
  }

  public void registerSpan(int threadId, DDTraceId traceId, long spanId) {
    long spanIdOffset = getSpanIdOffset(threadId);
    long traceIdOffset = getTraceIdOffset(threadId);

    tls.setLong(spanIdOffset, spanId);
    tls.setLong(traceIdOffset, traceId.toLong()); // low bits
    tls.setLong(traceIdOffset + 8, traceId.toHighOrderLong()); // high bits
  }

  public void registerSpan(DDTraceId traceId, long spanId) {
    registerSpan(getTID(), traceId, spanId);
  }

  public long getSpanId(int threadId) {
    long offset = getSpanIdOffset(threadId);
    return tls.getLong(offset);
  }

  public long getSpanId() {
    return getSpanId(getTID());
  }

  public DDTraceId getTraceId(int threadId) {
    long offset = getTraceIdOffset(threadId);
    long lowBits = tls.getLong(offset);
    long highBits = tls.getLong(offset + 8);
    return DD128bTraceId.from(highBits, lowBits);
  }

  public DDTraceId getTraceId() {
    return getTraceId(getTID());
  }
}
