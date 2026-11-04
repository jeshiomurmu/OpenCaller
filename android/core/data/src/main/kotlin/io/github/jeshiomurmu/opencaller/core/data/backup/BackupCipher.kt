package io.github.jeshiomurmu.opencaller.core.data.backup

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The passphrase is wrong, or the file was changed (AES-GCM's tag doesn't match). */
class WrongPassphraseException(
    cause: Throwable,
) : Exception("wrong passphrase or damaged backup", cause)

/**
 * Encrypts backups with the user's passphrase (docs/prd.md F-UT-05; the same file for Drive and for a file the user
 * picks): a 256-bit key from PBKDF2-HMAC-SHA256 (Android's own `PBKDF2withHmacSHA256`, API 26+; no extra library)
 * with a random 16-byte salt, then AES-256-GCM with a random 12-byte nonce and the header as associated data. The
 * file: `OCBK` · format (1 byte) · iterations (4 bytes) · salt (16) · nonce (12) · ciphertext with the 16-byte tag.
 * Without the passphrase nobody (not Google, not OpenCaller) can read it; a forgotten passphrase can't be recovered.
 */
class BackupCipher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Encrypts [plain] with [passphrase]. */
    fun encrypt(
        plain: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header =
            ByteBuffer
                .allocate(HEADER_BYTES)
                .put(MAGIC)
                .put(FORMAT)
                .putInt(iterations)
                .put(salt)
                .put(nonce)
                .array()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    /** Decrypts [file]; throws [BackupFormatException] for a file that isn't a backup, [WrongPassphraseException] otherwise. */
    fun decrypt(
        file: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        if (file.size < HEADER_BYTES + TAG_BITS / Byte.SIZE_BITS || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            unreadable("not an OpenCaller backup")
        }
        val buffer = ByteBuffer.wrap(file)
        buffer.position(MAGIC.size)
        if (buffer.get() != FORMAT) unreadable("unknown backup encryption")
        val rounds = buffer.int
        if (rounds !in MIN_ITERATIONS..MAX_ITERATIONS) unreadable("unexpected key derivation")
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, rounds), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(file, 0, HEADER_BYTES)
            cipher.doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
        } catch (e: GeneralSecurityException) {
            throw WrongPassphraseException(e)
        }
    }

    private fun key(
        passphrase: CharArray,
        salt: ByteArray,
        rounds: Int,
    ): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, rounds, KEY_BITS)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Parameters. */
    companion object {
        /** OWASP's 2023 figure for PBKDF2-HMAC-SHA256 (a second or two on a phone, once per backup or restore). */
        const val DEFAULT_ITERATIONS = 600_000

        /** Shortest passphrase the screen accepts. */
        const val MIN_PASSPHRASE_LENGTH = 8

        private const val MIN_ITERATIONS = 1_000
        private const val MAX_ITERATIONS = 10_000_000
        private const val KDF = "PBKDF2withHmacSHA256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val FORMAT: Byte = 1
        private val MAGIC = "OCBK".toByteArray(Charsets.US_ASCII)
        private val HEADER_BYTES = MAGIC.size + 1 + Int.SIZE_BYTES + SALT_BYTES + NONCE_BYTES
    }
}
