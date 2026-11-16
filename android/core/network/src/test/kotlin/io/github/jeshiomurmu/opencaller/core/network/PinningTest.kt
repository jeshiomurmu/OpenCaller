package io.github.jeshiomurmu.opencaller.core.network

import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Public-key pinning of play release builds (docs/rules.md §7, docs/security.md MASVS-NETWORK-2): the server's key
 * or the backup key is accepted, any other key is refused, also when a CA the phone trusts signed it. The two
 * certificates are throwaway self-signed ones made for this test (`src/test/resources/pinning`, no private keys).
 */
class PinningTest {
    private val current = certificate("a")
    private val other = certificate("b")

    private fun network(vararg pins: String) = OpenCallerNetwork(ServerConfig(BASE_URL, pins.toList()), MemoryTokenStore(), { 0L })

    private fun certificate(name: String): X509Certificate =
        checkNotNull(javaClass.getResourceAsStream("/pinning/$name.pem")).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    @Test
    fun theServersKeyIsAcceptedAndAnyOtherKeyRefused() {
        val pinner = checkNotNull(network(PIN_A, BACKUP_PIN).certificatePinner)
        pinner.check(HOST, listOf(current))
        assertThrows(SSLPeerUnverifiedException::class.java) { pinner.check(HOST, listOf(other)) }
    }

    @Test
    fun theBackupPinTakesOverWhenTheKeyIsRotated() {
        val pinner = checkNotNull(network(BACKUP_PIN, PIN_B).certificatePinner)
        pinner.check(HOST, listOf(other))
        assertThrows(SSLPeerUnverifiedException::class.java) { pinner.check(HOST, listOf(current)) }
    }

    @Test
    fun pinsCoverTheServersHostOnly() {
        // Presigned picture links and push endpoints are other hosts (system trust); the API host is always pinned.
        val pinner = checkNotNull(network(PIN_A).certificatePinner)
        pinner.check("cdn.example.net", listOf(other))
        assertThrows(SSLPeerUnverifiedException::class.java) { pinner.check(HOST, listOf(other)) }
    }

    @Test
    fun noPinsMeansSystemTrust() {
        // foss builds and debug builds: no pinner (self-hosters may use their own CA).
        assertNull(network().certificatePinner)
    }

    private companion object {
        const val HOST = "api.example.org"
        const val BASE_URL = "https://$HOST/"
        const val PIN_A = "sha256/ctUsYMuVwewWSgWvBJMuVFFtSjDyG65y6N8OMUGsicM="
        const val PIN_B = "sha256/e0kDDGqOppqMD1sT+E/aDOYIXStm2jy4iDxntc0rPoY="

        // A backup key that matches neither certificate (kept offline by the operator).
        const val BACKUP_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
