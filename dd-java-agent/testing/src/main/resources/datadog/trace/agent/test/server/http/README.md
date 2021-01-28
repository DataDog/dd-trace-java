Following https://medium.com/vividcode/enable-https-support-with-self-signed-certificate-for-embedded-jetty-9-d3a86f83e9d9

Generated using

```
keytool -keystore datadog.jks -alias test -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -genkey -validity 36500
```

Password: `datadog`

```
CN=localhost, OU=ou, O=o, L=l, ST=st, C=c
```
