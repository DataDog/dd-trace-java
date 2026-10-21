/*
 * Copyright (c) 2026 OkHttp Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package datadog.communication.http;

import static java.util.Arrays.asList;

import java.security.cert.Certificate;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import okhttp3.internal.tls.OkHostnameVerifier;
import okio.ByteString;

/**
 * Rejects the compromised eMudhra keys from CVE-2026-111943 before sending HTTP data.
 *
 * <p>Adapted from Jesse Wilson's corrected mitigation:
 * https://gist.github.com/swankjesse/de8828e357a4d3fc8ebe1ca14ec2bc9f
 */
public final class ForbiddenCertificatesHostnameVerifier implements HostnameVerifier {
  public static final ForbiddenCertificatesHostnameVerifier INSTANCE =
      new ForbiddenCertificatesHostnameVerifier(
          new HashSet<>(
              asList(
                  ByteString.decodeHex(
                      "43bffa19d6ef651451f6841e019931e96f6f2eaeff28ab4b6f1ce4576de2717b"),
                  ByteString.decodeHex(
                      "49dd353dc2b919a3770645117d0e60c16115e7d1bc259eefe734b00ea434fe5f"),
                  ByteString.decodeHex(
                      "5c0660a2b164910d8ff589c18e14552e9e0ec237dcefe2cb736d41a4811f81a0"),
                  ByteString.decodeHex(
                      "63ecefee431d49523bc1136c4e72d0206a477086caf606cd5cf025686c109cf8"),
                  ByteString.decodeHex(
                      "92c063d67e8f14db71d288e11266ffdaf69cbf115f7bab14f3bc2f68d25e0e30"),
                  ByteString.decodeHex(
                      "e0254c7fcdc265e2f0f8a428dabb75ab4ae039b4872d62f5ee57f0877d5a8a41"),
                  ByteString.decodeHex(
                      "ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105"),
                  ByteString.decodeHex(
                      "ec4ffa1ad0ac88081314b7aaffef6a91f97cd57291bb5ba686c5415f5eb4f6b6"))));

  private final Set<ByteString> forbiddenSpkiSha256s;

  ForbiddenCertificatesHostnameVerifier(Set<ByteString> forbiddenSpkiSha256s) {
    this.forbiddenSpkiSha256s = forbiddenSpkiSha256s;
  }

  @Override
  public boolean verify(String hostname, SSLSession session) {
    if (!OkHostnameVerifier.INSTANCE.verify(hostname, session)) {
      return false;
    }

    try {
      // Match keys throughout the chain, including cross-signed certificates.
      for (Certificate certificate : session.getPeerCertificates()) {
        ByteString spkiSha256 = ByteString.of(certificate.getPublicKey().getEncoded()).sha256();
        if (forbiddenSpkiSha256s.contains(spkiSha256)) {
          return false;
        }
      }
      return true;
    } catch (SSLPeerUnverifiedException e) {
      return false;
    }
  }
}
