package com.example.cryptobench.jmh;

import com.example.cryptobench.shared.Fixtures;
import com.example.cryptobench.shared.Tls;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
public class TlsHandshakeBenchmark {

  @Param({"jdk", "accp"})
  public String provider;

  @Param({"rsa", "ec"})
  public String cert;

  @Param({"TLSv1.3"})
  public String protocol;

  @Param({"default", "secp256r1"})
  public String group;

  private SSLContext server;
  private SSLContext client;
  private byte[] appData;

  @Setup
  public void setup() throws Exception {
    ProviderSetup.apply(provider);
    server = Tls.serverContext("ec".equals(cert) ? Fixtures.EC_STORE : Fixtures.RSA_STORE);
    client = Tls.trustAllClientContext();
    appData = Fixtures.bytes(1024, 3);
    handshake();
  }

  @Benchmark
  public String handshake() throws Exception {
    return Tls.handshake(server, client, protocol, "default".equals(group) ? null : group, appData);
  }
}
