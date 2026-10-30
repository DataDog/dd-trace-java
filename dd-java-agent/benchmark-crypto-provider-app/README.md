# Crypto provider benchmark (ACCP POC)

POC question: can the agent make apps faster, without changing what they compute, by putting a native JCA
provider (Amazon Corretto Crypto Provider, ACCP 2.5.0, backed by AWS-LC) ahead of the JDK providers?

Feature: `DD_TRACE_EXPERIMENTAL_CRYPTO_PROVIDER_ENABLED` / `-Ddd.trace.experimental.crypto.provider.enabled`,
default `false`. With it on, the agent inserts ACCP at position 1 of `Security.getProviders()` when ACCP is on
the application's (system) class path. Code: `CryptoProviderInstaller` in `dd-java-agent/agent-tooling` and
`Agent.maybeInstallCryptoProvider` in `agent-bootstrap`.

This directory is a standalone Gradle build. The root `settings.gradle.kts` does not include it, so it can't break
the repo build. Everything runs on JDK 21. ACCP's production platform is Linux, so the measurement runs in a Linux
container (`eclipse-temurin:21-jdk`, `linux/arm64` by default to match Apple silicon). Colima works as the Docker
runtime.

## What is in here

| path | what |
|---|---|
| `src/shared` | fixed keys (`keys/*.p12`, self-signed RSA-2048 and ECDSA P-256, password `changeit`, test-only), payloads, a tiny JWS (RS256/ES256) helper, in-memory SSLEngine handshake |
| `src/main` | Spring Boot 3 demo app, run from a flat class path (like a Jib image) so the system class loader sees ACCP |
| `src/loadgen` | load generator, startup probe, fake trace agent, report (plain JDK, JDK providers, runs in its own JVM) |
| `src/jmh` | JMH microbenchmarks without the agent: JDK vs ACCP at position 1 |
| `src/test` | differential tests: JDK vs ACCP outputs, TLS interop with a JDK-only peer JVM |
| `run-bench.sh` | demo app under `noagent` / `agent` / `agent-accp` (+ optional `noagent-accp`) |
| `run-jmh.sh`, `run-difftest.sh`, `check-agent-install.sh` | see below |
| `in-docker.sh` | runs any of the scripts in the Linux container, with the repo mounted at the same path |

## Exact commands for the measurement step

Run on a quiet machine (laptop plugged in, other apps closed). Nothing else should build or benchmark at the same time.

```shell
# 0. once, on the host: agent jar + bench artifacts
cd <repo root>
./gradlew :dd-java-agent:shadowJar                      # -> dd-java-agent/build/libs/dd-java-agent-*-SNAPSHOT.jar
cd dd-java-agent/benchmark-crypto-provider-app
./build.sh                                              # ~10 s once dependencies are cached
colima status || (colima start 2>&1 && while ! docker info &>/dev/null; do sleep 1; done)
AGENT=$(ls ../build/libs/dd-java-agent-*-SNAPSHOT.jar)

# 1. correctness, ~15 s: 34 tests must pass
./in-docker.sh ./run-difftest.sh

# 2. the flag does what it says, ~1 min: every line must say "yes"
./in-docker.sh ./check-agent-install.sh -a "$AGENT"

# 3. library-level ceiling, no agent, ~20 min. Results in results/jmh-<timestamp>/
./in-docker.sh ./run-jmh.sh

# 4. demo app, ~10 min per pass with the default 3 configs (~14 min with noagent-accp).
#    Run it twice with the config order reversed, as for the regex POC: order effects are large.
./in-docker.sh ./run-bench.sh -a "$AGENT" -o results/pass1 \
  --configs noagent,agent,agent-accp,noagent-accp
./in-docker.sh ./run-bench.sh -a "$AGENT" -o results/pass2-reversed \
  --configs noagent-accp,agent-accp,agent,noagent
```

Optional variants:

- `--cert ec` serves the HTTPS endpoint with the ECDSA P-256 certificate instead of RSA-2048.
- `--endpoints nocrypto,jwt,aesgcm,tls,tls12` adds the TLS 1.2 handshake endpoint.
- `DOCKER_OPTS="--cpuset-cpus 0-3"` pins the container. The app and the load generator share the container's
  CPUs. CPU µs per request (server process only) is the number that does not depend on load generator saturation.
- `BENCH_PLATFORM=linux/amd64` with an x86_64 host. Under emulation the numbers mean nothing.
- Without Docker on a Linux host: drop `./in-docker.sh` (scripts pick the ACCP jar for `uname -sm`; a JDK 21 must
  be in `BENCH_JAVA_HOME` or `JAVA_HOME`).

`run-bench.sh` options:

| option | default |
|---|---|
| `-a JAR` | dd-java-agent jar, required unless `--configs` has no agent config |
| `-o DIR` | `./results/<timestamp>` |
| `--startup-runs N` | 5 startup runs per config |
| `--warmup S` / `--duration S` | 15 s warmup and 30 s measured per endpoint |
| `--connections N` | 8 concurrent connections |
| `--configs LIST` | `noagent,agent,agent-accp` (also `noagent-accp`) |
| `--endpoints LIST` | `nocrypto,jwt,aesgcm,tls` (also `tls12`) |
| `--cert rsa\|ec` | `rsa` |
| `--heap SIZE` | `512m` for `-Xms`/`-Xmx` |

Environment: `BENCH_JAVA_HOME` (JDK 21 for the app and tools), `APP_PORT` (18080), `HTTPS_PORT` (18443),
`FAKE_AGENT_PORT` (18126). `in-docker.sh` also reads `BENCH_IMAGE` (default `eclipse-temurin:21-jdk`),
`BENCH_PLATFORM` (default `linux/arm64`) and `DOCKER_OPTS` (extra `docker run` flags). `run-jmh.sh` writes to
`results/jmh-<timestamp>/` unless `JMH_OUT` is set, and passes any JMH arguments or benchmark regex through.
`build.sh` writes `build/app/lib` (app, no ACCP), `build/accp` (ACCP for every platform),
`build/libs/crypto-bench-tools.jar`, `build/libs/crypto-bench-jmh.jar` and `build/testbundle`.

Smoke runs (numbers mean nothing):

```shell
./in-docker.sh ./run-jmh.sh -f 1 -wi 1 -i 1 -w 1 -r 1
./in-docker.sh ./run-bench.sh -a "$AGENT" --startup-runs 2 --warmup 1 --duration 2 --connections 2
```

## The demo app and `run-bench.sh`

| endpoint | per-request work |
|---|---|
| `/nocrypto` | control: returns a fixed ~300 byte string |
| `/jwt` | verifies an RS256 JWT: `Signature.getInstance("SHA256withRSA")` per request, as JWT libraries do. The public key is parsed once at startup by whichever provider `KeyFactory` resolves to (like a JWKS cache) |
| `/aesgcm` | AES-256-GCM encrypt of a 4 KB payload with a fresh `Cipher`; IV = request counter, so `?n=42` is deterministic |
| `tls` (HTTPS port, `/tls`) | the load generator opens a **new TLS 1.3 connection per request** (`Connection: close`, and a unique peer host so the client never resumes a session). Tomcat's JSSE connector (not OpenSSL) does a full handshake every time. `tls12` does the same with TLS 1.2 |

| config | what runs |
|---|---|
| `noagent` | the app alone |
| `agent` | `-javaagent`, `-Ddd.trace.experimental.crypto.provider.enabled=false` |
| `agent-accp` | same as `agent`, with the flag `true` |
| `noagent-accp` | no agent; the app calls `Security.insertProviderAt(ACCP, 1)` first thing in `main()`. It is the ceiling for a manual install, and it separates "ACCP helps" from "the agent costs" |

Every config uses the same JVM flags (`-Xms512m -Xmx512m -XX:+UseG1GC`) and the same class path, which includes the
ACCP jar. Only the flag differs. The agent baseline matches the regex POC: tracing on, spans sent to `FakeAgent`, and
RC, telemetry, profiling, AppSec, IAST, DI, DSM, JMXFetch, health metrics, stats, crash tracking and startup logs off.

For each config, `run-bench.sh` runs these steps in order:

1. **Startup**: median time from process spawn to the first `200` from `/nocrypto` (1 discarded + `--startup-runs`).
2. **Verify**: hashes the deterministic responses (`/nocrypto`, `/jwt`, `/jwt?tamper=true`, `/aesgcm?n=7|42`, and
   `/tls` over TLS 1.3 and 1.2), and reads `/admin/providers` (provider at position 1, the provider each
   benchmarked algorithm resolves to, and the class of the JWT key) right after the app first answers. The report
   fails (exit 3) if any response hash differs between configs. This is the feature on vs off differential check
   at the app level.
3. **Load**: per endpoint, `--warmup` then `--duration` closed loop with `--connections` connections. It records
   req/s, p50/p90/p99/max latency, server process CPU over the measured window (µs/request), and GC.

Outputs go to `results/<timestamp>/` (or `-o DIR`): `results.tsv`, `summary.json`, `summary.md` (startup, load with
deltas against the first config, response equivalence, provider resolution), and `logs/`.

## JMH (`run-jmh.sh`): library ceiling without the agent

`provider=jdk` is the default provider list. `provider=accp` calls `Security.insertProviderAt(ACCP, 1)` in the
fork, which is exactly what the flag does, and asserts that `SHA256withRSA` resolves to ACCP. Every op includes
`getInstance` and `init`, as per-message application code does.

- `CryptoPrimitivesBenchmark`: AES-256-GCM encrypt 1 KB / 16 KB, SHA-256 1 KB, HMAC-SHA256 1 KB, ES256 sign and verify
  (ECDSA P-256, P1363 format as in JWS), RS256 verify and sign (RSA-2048).
- `TlsHandshakeBenchmark`: one full TLS 1.3 handshake between two in-memory `SSLEngine`s plus one 1 KB record each
  way. Parameters are `cert=rsa|ec` and `group=default|secp256r1`. JSSE's default group is x25519, and ACCP has no
  XDH, so with `default` the key exchange stays in SunEC and only signatures, HKDF/HMAC and AES-GCM move to ACCP.
  Both engines are in one JVM, so with `accp` both sides use it.

Default settings are 2 forks × (5 × 2 s warmup + 5 × 2 s measurement) per combination, 24 combinations, about 20
minutes. Any JMH argument is passed through (`./run-jmh.sh -p provider=accp TlsHandshake`).

Smoke run in the container (1 fork, 1 × 1 s iterations, Colima 4 vCPU on Apple silicon). These numbers are only
there to show the direction: re-measure before quoting any of them.

| benchmark (µs/op) | jdk | accp |
|---|---:|---:|
| aesGcmEncrypt1k | 1.15 | 0.80 |
| aesGcmEncrypt16k | 4.57 | 3.22 |
| sha256_1k | 0.39 | 0.48 |
| hmacSha256_1k | 0.60 | 0.65 |
| es256Sign | 81.2 | 10.1 |
| es256Verify | 258.8 | 27.5 |
| rs256Verify | 24.5 | 14.2 |
| rs256Sign | 591 | 557 |
| TLS 1.3 handshake, rsa, x25519 | 1317 | 1078 |
| TLS 1.3 handshake, rsa, secp256r1 | 1724 | 872 |
| TLS 1.3 handshake, ec, x25519 | 895 | 465 |
| TLS 1.3 handshake, ec, secp256r1 | 1491 | 219 |

Small SHA-256/HMAC inputs look slower with ACCP: the JNI crossing costs more than the JDK's SHA intrinsics save.
Hashing-heavy apps with small inputs could get slower.

## Differential tests (`run-difftest.sh`)

`JcaDifferentialTest` names each provider explicitly and leaves the global list alone. Inputs run from 0 bytes to 1 MB.

- SHA-1/256/384/512: byte-identical one-shot and in 7-byte chunks. HMAC-SHA1/256/384/512 with 5 key lengths:
  byte-identical.
- AES-128/256-GCM with fixed key, IV and AAD: byte-identical ciphertext and tag. Each provider decrypts the other's
  output. A flipped tag bit raises `AEADBadTagException` in both.
- AES-CBC with PKCS5Padding and NoPadding: byte-identical, and each provider decrypts the other's output.
- ECDSA P-256 (DER and P1363, SHA-256/384): signed with one provider, verified with the other, both ways, using
  each provider's own key objects and the other's. Tampered messages are rejected.
- RSA-2048 PKCS#1 v1.5 (SHA-256/512): byte-identical signatures (the scheme is deterministic), cross-verified.
  Tampered signatures are rejected.
- ECDH P-256: the shared secret is identical across the JDK and ACCP, in both directions, with JDK-generated and
  ACCP-generated keys.

`TlsInteropTest` installs ACCP at position 1 in the test JVM. A child JVM runs as the JDK-only peer, and the test
asserts that the child's `providers[0]` is not ACCP. Both directions (ACCP server with JDK client, JDK server with
ACCP client) are run for TLS 1.3 and 1.2, RSA and EC certificates, and x25519 and secp256r1. The test checks the
negotiated protocol and an echo round trip.

## How the agent installs it, and the trade-off

- **When**: `Agent.start` schedules the install after a start delay, like JMXFetch:
  `DD_TRACE_EXPERIMENTAL_CRYPTO_PROVIDER_START_DELAY` / `-Ddd.trace.experimental.crypto.provider.start-delay`,
  in seconds, default 15. The delay gives the app's `main()` time to register its own providers, set
  `security.provider.N` or `java.util.logging.manager`, and install a default file system before the installer
  reads the provider list or initialises ACCP. Initialising ACCP uses `java.util.logging` and `java.nio.file` (it
  extracts its `.so` to `java.io.tmpdir`), which `docs/bootstrap_design_guidelines.md` bans in premain, so it
  never runs on the premain thread, even with a delay of 0. If the app uses a custom JUL log manager
  (JBoss/WildFly and similar, detected the same way as for JMXFetch), the delay starts once `LogManager` is
  loaded, with the existing one-minute fail-safe. The scripts here set the delay to 0, which is what the
  recorded results measured: install starts right after agent startup.
- **Who misses out**: provider selection happens at each `getInstance` call, so anything the app or JSSE looks up
  after installation uses ACCP. That covers per-request `Signature`/`Cipher`/`Mac` and every JSSE handshake, even
  on an `SSLContext` created earlier. Objects created before installation keep their JDK provider: a `Cipher`,
  `Signature` or `SecureRandom` cached in a static, or keys parsed by the JDK `KeyFactory`. ACCP still uses foreign
  keys, but it has to translate them. In the demo the install is usually done before Spring has finished starting,
  and `/admin/providers` shows the state at first request. A synchronous install in premain would close the gap,
  but it breaks the JUL/NIO rules.
- **Cost**: smoke check in the container: 270-380 ms on the background thread when ACCP is installed (extracting
  and loading the native library, AWS-LC self-tests). This competes with app startup for CPU, so the startup median
  is the number to watch. With the flag on and no ACCP jar, the installer only does one class-path resource lookup.
- **Skips**: the agent does nothing when ACCP is not visible to the system class loader, when the kernel FIPS flag
  is set (`/proc/sys/crypto/fips_enabled`), when a provider whose name contains `FIPS` is registered, when
  `org.bouncycastle.fips.approved_only=true` or ACCP is a FIPS build, when `-Djava.security.properties` is set, when
  a non-JDK provider is already registered (the app manages its own providers), when ACCP is already registered,
  or when ACCP's `getLoadingError()` is non-null or `assertHealthy()` (native self-tests) fails. The provider-list
  checks run again after ACCP initialises, under the `Security` class lock, so a provider the app registers while
  ACCP loads still wins.
- **Only primitives change**: ACCP implements primitives only. JSSE's TLS state machine, `KeyStore`, `CertificateFactory`
  and `SSLContext` stay with the JDK. That is why ACCP was chosen over Conscrypt. Conscrypt replaces the TLS stack
  itself (`SSLContext`/`SSLEngine` over BoringSSL), which changes far more observable behavior (cipher suite
  defaults, session handling, ALPN, error messages) and would also need native libraries. Since ACCP runs on Linux
  x86_64/aarch64 (and has macOS builds for development), Conscrypt was not needed for this POC.
- **Behavior that can change** (none seen in the tests, but the default does not prove it): `new SecureRandom()`
  becomes ACCP's AWS-LC DRBG, `KeyFactory` returns ACCP key classes (code that casts to `sun.security.*` classes
  would break), exception types and messages on misuse can differ, and the agent's own crypto use (e.g.
  `UUID.randomUUID()`, already a forbidden API in the agent for this reason) goes through ACCP.

## What shipping it for real would need

- **Bundling**: the POC uses whatever ACCP jar the app already has. To ship it, the agent would carry the native
  libraries itself. ACCP jars are per platform: linux-x86_64 and linux-aarch_64 for production (osx builds exist for
  development, there is no Windows build). Each linux jar is about 3.2 MB compressed with an 8.8 MB `.so`, so both
  add about 6.5 MB to a 36 MB agent jar, or the agent downloads them out of band. The provider class would be
  loaded from the agent class loader instead of the system class loader. ACCP extracts its library to
  `java.io.tmpdir`, which fails on read-only file systems (the health check then skips installation). It would also
  need a musl/Alpine story (the published `.so` is glibc), checks for old glibc, and a crash-safety review of a
  second native library in every customer JVM.
- **Signing**: the ACCP jar is signed (`META-INF/PROVIDER.SF`). OpenJDK doesn't check JCE provider signatures, but
  Oracle JDK does for `Cipher`/`Mac`/`KeyAgreement` providers. Repackaging or shading into the agent jar would break
  the signature, so Oracle JDK support would need the original signed jar on disk.
- **FIPS**: never install on a FIPS host or JDK. A FIPS story would need ACCP-FIPS, which uses the FIPS-validated
  AWS-LC module, plus customer opt-in, and is out of scope.
- **Licensing**: ACCP is Apache-2.0 and AWS-LC is Apache-2.0/ISC/OpenSSL-style. Bundling would need
  `LICENSE-3rdparty.csv` and NOTICE updates and a legal review of shipping native crypto (export classification).
- **Operations**: telemetry for the install outcome and duration, a kill switch through remote config (it can only
  stop future installs, since removing a provider live is not safe), and a JDK-version allowlist.
- **Agent side effects already present**: when ACCP is on the system class path, `Agent.okHttpMayIndirectlyLoadJUL()`
  sees `META-INF/services/java.security.Provider` and delays OkHttp by 100 ms, whatever the flag says. That applies
  to both `agent` and `agent-accp`, so it doesn't bias the comparison.
