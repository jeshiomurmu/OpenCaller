package io.github.jeshiomurmu.opencaller.core.database.crypto

/** Encrypts the database passphrase at rest. Production uses [AndroidKeystoreCipher]. */
interface PassphraseCipher {
    /** Encrypts [plaintext]; the result contains everything [decrypt] needs (e.g. the IV). */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Reverses [encrypt]; throws if [ciphertext] was tampered with. */
    fun decrypt(ciphertext: ByteArray): ByteArray
}
