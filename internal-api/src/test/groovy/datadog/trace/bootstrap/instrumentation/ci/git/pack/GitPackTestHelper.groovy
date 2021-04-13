package datadog.trace.bootstrap.instrumentation.ci.git.pack

import java.util.zip.Inflater

class GitPackTestHelper {

  static def 'content_5b6f3a6dab5972d73a56dff737bd08d995255c08'() {
    return """tree c52914110869ff3999bca4837410511f17787e87
parent 98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c
author Tony Redondo <tony.redondo@datadoghq.com> 1614364333 +0100
committer GitHub <noreply@github.com> 1614364333 +0100
gpgsig -----BEGIN PGP SIGNATURE-----
 
 wsBcBAABCAAQBQJgOT6tCRBK7hj4Ov3rIwAAdHIIAJblx5QSlwh1Z/dd2kk1WCZd
 /4JV1ktMFWJHnJPa3L5AR6A0Aatbp0LMaaQfrLztAthn3JjPgHwm4MdHIj8cxhYP
 Z/I+z1yZfCe1UTBmefgodEpNKjwTWxyOPSabj/MaUTS1Scry/8E7qdY/Z68Vv4WG
 CsTKxAOHgHBNpkWPdRwxmmJeUr137HX/fZ3jFccoHLR+AMTpP/2tooAY0VYB2mLl
 5crYrisdAyulXzkBH+dbAuDC2r1z/OcYrM7+WCDCfG9bOZkn/iSnNHBzgubUXlCH
 3CuZfvUaVuoxeLou/PTXLK8T9d6DXqYUi8VhsPFxkoGbshfShfjPdPQBXcbFJ18=
 =Xwne
 -----END PGP SIGNATURE-----
 

Adding Git information to test spans (#1242)\n\n* Initial basic GitInfo implementation.\r\n\r\n* Adds Author, Committer and Message git parser.\r\n\r\n* Changes based on the review."""
  }

  static def "inflate"(byte[] deflated) {
    if (deflated == null) {
      return null
    }

    final ByteArrayOutputStream baos
    try {
      baos = new ByteArrayOutputStream()
      final Inflater ifr = new Inflater()
      ifr.setInput(deflated)

      final byte[] tmp = new byte[4 * 1024]
      while (!ifr.finished()) {
        final int size = ifr.inflate(tmp)
        baos.write(tmp, 0, size)
      }

      final byte[] decompressed = baos.toByteArray()
      return new String(decompressed)
    } finally {
      if (baos != null) {
        baos.close()
      }
    }
  }
}
