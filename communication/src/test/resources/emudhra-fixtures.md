# Certificate fixtures

`emudhra-compromised-certificates.pem` contains the public certificates from
[Mozilla's incident report](https://groups.google.com/a/mozilla.org/g/dev-security-policy/c/0R3XLJL6FuE/m/0XofdebrAAAJ).
The `pem` entries were extracted from the attached `impacted-intermediates.json`
on 2026-10-21. Their SHA-256 SPKI hashes were checked against that attachment.
The bundle includes cross-signed certificates sharing the compromised keys.

`localhost.p12` contains an unrelated, self-signed test certificate and private key.
It allows the test to block a real HTTPS connection without using compromised private keys.
Its password is `datadog`. Generate it with:

```shell
keytool -genkeypair -alias localhost -keyalg RSA -keysize 2048 \
  -dname CN=localhost -ext SAN=dns:localhost \
  -startdate '2020/01/01 00:00:00' -validity 36500 \
  -storetype PKCS12 -keystore localhost.p12 \
  -storepass datadog -keypass datadog
```
