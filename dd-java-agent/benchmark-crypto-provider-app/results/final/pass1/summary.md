# Crypto provider benchmark

- date: 2026-10-31T01:05:02Z
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
| noagent | 807 | 789 | 821 | 5 |
| agent | 1464 | 1443 | 1477 | 5 |
| agent-accp | 1433 | 1401 | 1461 | 5 |

## Load (relative columns vs `noagent`)

| endpoint | config | req/s | req/s vs base | p50 us | p99 us | CPU us/req | CPU/req vs base | GC ms | errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| nocrypto | noagent | 78305 | - | 94.4 | 266.1 | 19.03 | - | 57 | 0 |
| nocrypto | agent | 65688 | -16.1% | 107.6 | 620.1 | 25.75 | +35.3% | 143 | 0 |
| nocrypto | agent-accp | 65582 | -16.2% | 107.3 | 622.3 | 25.94 | +36.3% | 151 | 0 |
| jwt | noagent | 49654 | - | 121.0 | 1157.7 | 44.02 | - | 62 | 0 |
| jwt | agent | 45463 | -8.4% | 128.9 | 1466.8 | 51.19 | +16.3% | 113 | 0 |
| jwt | agent-accp | 52250 | +5.2% | 110.8 | 1141.8 | 42.16 | -4.2% | 110 | 0 |
| aesgcm | noagent | 67095 | - | 108.8 | 527.4 | 23.24 | - | 85 | 0 |
| aesgcm | agent | 64268 | -4.2% | 92.5 | 701.6 | 28.31 | +21.8% | 203 | 0 |
| aesgcm | agent-accp | 62733 | -6.5% | 99.2 | 676.1 | 28.53 | +22.8% | 187 | 0 |
| tls | noagent | 2311 | - | 3287.4 | 6990.6 | 875.94 | - | 40 | 0 |
| tls | agent | 2226 | -3.7% | 3432.3 | 7095.8 | 895.56 | +2.2% | 44 | 0 |
| tls | agent-accp | 2270 | -1.8% | 3356.2 | 7080.8 | 873.55 | -0.3% | 65 | 0 |

## Response equivalence (SHA-256 of each deterministic response)

| check | identical | noagent | agent | agent-accp | body |
|---|---|---|---|---|---|
| nocrypto | yes | c0d69bd32d04 | c0d69bd32d04 | c0d69bd32d04 | `field0=0;field1=31;field2=62;field3=93;field4=124;field5=...` |
| jwt | yes | 6ac94d01b36d | 6ac94d01b36d | 6ac94d01b36d | `valid {"sub":"bench-user","iss":"https://issuer.example",...` |
| jwt-tampered | yes | f1234d75178d | f1234d75178d | f1234d75178d | `invalid` |
| aesgcm-n7 | yes | ffa2a5a04cf3 | ffa2a5a04cf3 | ffa2a5a04cf3 | `4112 bd78211b22a822f78975ca35b56ae63e` |
| aesgcm-n42 | yes | 693a1c79e2d1 | 693a1c79e2d1 | 693a1c79e2d1 | `4112 fe3d5d488acafa0f25486eec0d976df5` |
| tls13 | yes | 2689367b205c | 2689367b205c | 2689367b205c | `ok` |
| tls12 | yes | 2689367b205c | 2689367b205c | 2689367b205c | `ok` |

## Which provider each algorithm resolves to (checked right after the app first answers)

| lookup | noagent | agent | agent-accp |
|---|---|---|---|
| Cipher.AES/GCM/NoPadding | SunJCE | SunJCE | AmazonCorrettoCryptoProvider |
| KeyAgreement.ECDH | SunEC | SunEC | AmazonCorrettoCryptoProvider |
| KeyAgreement.XDH | SunEC | SunEC | SunEC |
| Mac.HmacSHA256 | SunJCE | SunJCE | AmazonCorrettoCryptoProvider |
| MessageDigest.SHA-256 | SUN | SUN | AmazonCorrettoCryptoProvider |
| Signature.SHA256withRSA | SunRsaSign | SunRsaSign | AmazonCorrettoCryptoProvider |
| jwtKeyClass | sun.security.rsa.RSAPublicKeyImpl | sun.security.rsa.RSAPublicKeyImpl | com.amazon.corretto.crypto.provider.EvpRsaPublicKey |
| position1 | SUN | SUN | AmazonCorrettoCryptoProvider |
| providerCount | 12 | 12 | 13 |
