package com.example.cryptobench.jmh;

import com.example.cryptobench.shared.Fixtures;
import com.example.cryptobench.shared.Jwt;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
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
public class CryptoPrimitivesBenchmark {

  @Param({"jdk", "accp"})
  public String provider;

  private byte[] payload1k;
  private byte[] payload16k;
  private byte[] rsJwtInput;
  private byte[] rsJwtSig;
  private byte[] esJwtInput;
  private byte[] esJwtSig;
  private KeyPair rsa;
  private KeyPair ec;
  private long ivCounter;

  @Setup
  public void setup() throws Exception {
    ProviderSetup.apply(provider);
    payload1k = Fixtures.bytes(1024, 1);
    payload16k = Fixtures.bytes(16 * 1024, 2);
    rsa = Fixtures.keyPair(Fixtures.RSA_STORE);
    ec = Fixtures.keyPair(Fixtures.EC_STORE);
    String rs = Jwt.sign("RS256", rsa.getPrivate(), Jwt.RS256_HEADER, Jwt.CLAIMS);
    String es = Jwt.sign("ES256", ec.getPrivate(), Jwt.ES256_HEADER, Jwt.CLAIMS);
    rsJwtInput = rs.substring(0, rs.lastIndexOf('.')).getBytes(StandardCharsets.US_ASCII);
    rsJwtSig = java.util.Base64.getUrlDecoder().decode(rs.substring(rs.lastIndexOf('.') + 1));
    esJwtInput = es.substring(0, es.lastIndexOf('.')).getBytes(StandardCharsets.US_ASCII);
    esJwtSig = java.util.Base64.getUrlDecoder().decode(es.substring(es.lastIndexOf('.') + 1));
  }

  private byte[] gcm(byte[] plaintext) throws Exception {
    byte[] iv = ByteBuffer.allocate(12).putInt(0x6a6d6800).putLong(++ivCounter).array();
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, Fixtures.AES_256, new GCMParameterSpec(128, iv));
    c.updateAAD(Fixtures.AAD);
    return c.doFinal(plaintext);
  }

  @Benchmark
  public byte[] aesGcmEncrypt1k() throws Exception {
    return gcm(payload1k);
  }

  @Benchmark
  public byte[] aesGcmEncrypt16k() throws Exception {
    return gcm(payload16k);
  }

  @Benchmark
  public byte[] sha256_1k() throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(payload1k);
  }

  @Benchmark
  public byte[] hmacSha256_1k() throws Exception {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(Fixtures.HMAC_KEY);
    return m.doFinal(payload1k);
  }

  @Benchmark
  public byte[] es256Sign() throws Exception {
    Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
    s.initSign(ec.getPrivate());
    s.update(esJwtInput);
    return s.sign();
  }

  @Benchmark
  public boolean es256Verify() throws Exception {
    Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
    s.initVerify(ec.getPublic());
    s.update(esJwtInput);
    return s.verify(esJwtSig);
  }

  @Benchmark
  public boolean rs256Verify() throws Exception {
    Signature s = Signature.getInstance("SHA256withRSA");
    s.initVerify(rsa.getPublic());
    s.update(rsJwtInput);
    return s.verify(rsJwtSig);
  }

  @Benchmark
  public byte[] rs256Sign() throws Exception {
    Signature s = Signature.getInstance("SHA256withRSA");
    s.initSign(rsa.getPrivate());
    s.update(rsJwtInput);
    return s.sign();
  }
}
