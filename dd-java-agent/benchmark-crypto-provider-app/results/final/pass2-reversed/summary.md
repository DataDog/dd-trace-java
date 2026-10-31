# Crypto provider benchmark

- date: 2026-10-31T01:14:38Z
- host: Linux 6.8.0-100-generic aarch64
- cpus: 4
- java: openjdk version 21.0.13 2026-10-20 LTS
- accp: AmazonCorrettoCryptoProvider-2.5.0-linux-aarch_64.jar
- httpsCert: rsa
- agentJar: <repo>/dd-java-agent/build/libs/dd-java-agent-1.68.0-SNAPSHOT.jar
- agentCommit: 9353e01423
- startupRuns: 5
- warmupS: 15
- durationS: 30
- connections: 8
- heap: 512m

## Startup to first successful request (ms)

| config | median | min | max | runs |
|---|---:|---:|---:|---:|
| agent-accp | 1403 | 1385 | 1456 | 5 |
| agent | 1480 | 1439 | 1540 | 5 |
| noagent | 794 | 779 | 812 | 5 |

## Load (relative columns vs `agent-accp`)

| endpoint | config | req/s | req/s vs base | p50 us | p99 us | CPU us/req | CPU/req vs base | GC ms | errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| nocrypto | agent-accp | 65051 | - | 108.2 | 610.3 | 26.14 | - | 151 | 0 |
| nocrypto | agent | 65766 | +1.1% | 107.2 | 602.2 | 25.87 | -1.0% | 136 | 0 |
| nocrypto | noagent | 78047 | +20.0% | 94.2 | 265.1 | 18.95 | -27.5% | 57 | 0 |
| jwt | agent-accp | 51190 | - | 112.7 | 1169.9 | 43.05 | - | 113 | 0 |
| jwt | agent | 45384 | -11.3% | 129.6 | 1473.4 | 51.58 | +19.8% | 114 | 0 |
| jwt | noagent | 49966 | -2.4% | 120.6 | 1160.1 | 44.04 | +2.3% | 62 | 0 |
| aesgcm | agent-accp | 61228 | - | 101.0 | 711.3 | 29.46 | - | 190 | 0 |
| aesgcm | agent | 62683 | +2.4% | 96.0 | 724.1 | 29.13 | -1.1% | 194 | 0 |
| aesgcm | noagent | 67820 | +10.8% | 107.8 | 505.7 | 23.21 | -21.2% | 86 | 0 |
| tls | agent-accp | 2280 | - | 3340.2 | 6966.1 | 872.50 | - | 66 | 0 |
| tls | agent | 2232 | -2.1% | 3415.5 | 7155.4 | 898.21 | +2.9% | 45 | 0 |
| tls | noagent | 2299 | +0.8% | 3302.4 | 7003.6 | 877.74 | +0.6% | 39 | 0 |

## Response equivalence (SHA-256 of each deterministic response)

| check | identical | agent-accp | agent | noagent | body |
|---|---|---|---|---|---|
| nocrypto | yes | c0d69bd32d04 | c0d69bd32d04 | c0d69bd32d04 | `field0=0;field1=31;field2=62;field3=93;field4=124;field5=...` |
| jwt | yes | 6ac94d01b36d | 6ac94d01b36d | 6ac94d01b36d | `valid {"sub":"bench-user","iss":"https://issuer.example",...` |
| jwt-tampered | yes | f1234d75178d | f1234d75178d | f1234d75178d | `invalid` |
| aesgcm-n7 | yes | ffa2a5a04cf3 | ffa2a5a04cf3 | ffa2a5a04cf3 | `4112 bd78211b22a822f78975ca35b56ae63e` |
| aesgcm-n42 | yes | 693a1c79e2d1 | 693a1c79e2d1 | 693a1c79e2d1 | `4112 fe3d5d488acafa0f25486eec0d976df5` |
| tls13 | yes | 2689367b205c | 2689367b205c | 2689367b205c | `ok` |
| tls12 | yes | 2689367b205c | 2689367b205c | 2689367b205c | `ok` |

## Which provider each algorithm resolves to (checked right after the app first answers)

| lookup | agent-accp | agent | noagent |
|---|---|---|---|
| Cipher.AES/GCM/NoPadding | AmazonCorrettoCryptoProvider | SunJCE | SunJCE |
| KeyAgreement.ECDH | AmazonCorrettoCryptoProvider | SunEC | SunEC |
| KeyAgreement.XDH | SunEC | SunEC | SunEC |
| Mac.HmacSHA256 | AmazonCorrettoCryptoProvider | SunJCE | SunJCE |
| MessageDigest.SHA-256 | AmazonCorrettoCryptoProvider | SUN | SUN |
| Signature.SHA256withRSA | AmazonCorrettoCryptoProvider | SunRsaSign | SunRsaSign |
| jwtKeyClass | com.amazon.corretto.crypto.provider.EvpRsaPublicKey | sun.security.rsa.RSAPublicKeyImpl | sun.security.rsa.RSAPublicKeyImpl |
| position1 | AmazonCorrettoCryptoProvider | SUN | SUN |
| providerCount | 13 | 12 | 12 |
