package smoketest

import java.security.MessageDigest

class IastController {

  def weakHash() {
    final hasher = MessageDigest.getInstance(params.algorithm as String)
    hasher.update('Hello World!'.bytes)
    return [message: "This is a hash : ${hasher.digest().toString()}"]
  }

}
