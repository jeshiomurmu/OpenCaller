package io.github.jeshiomurmu.opencaller.core.ml

import io.github.jeshiomurmu.opencaller.core.ml.assets.MlAssets
import io.github.jeshiomurmu.opencaller.core.ml.link.BadHostPrefixes
import io.github.jeshiomurmu.opencaller.core.ml.link.BrandCatalog
import io.github.jeshiomurmu.opencaller.core.ml.link.LinkChecker
import io.github.jeshiomurmu.opencaller.core.ml.transaction.BankTemplates
import io.github.jeshiomurmu.opencaller.core.ml.transaction.TransactionParser
import java.io.File

/** The real `core:ml` assets, read from the source tree (the build passes the directory as a system property). */
object SourceAssets : MlAssets {
    private val dir = File(checkNotNull(System.getProperty("opencaller.ml.assets")) { "opencaller.ml.assets not set" })

    override fun read(name: String): String = File(dir, name).readText(Charsets.UTF_8)
}

/** The components under test, wired to the real assets. */
object TestMl {
    /** A [TransactionParser] with the shipped bank templates. */
    fun transactionParser(): TransactionParser = TransactionParser(BankTemplates(SourceAssets))

    /** A [LinkChecker] with the shipped brands and the known-bad hosts [badHosts]. */
    fun linkChecker(vararg badHosts: String): LinkChecker {
        val listed =
            badHosts
                .map {
                    io.github.jeshiomurmu.opencaller.core.ml.link.Hosts
                        .prefix(it)
                }.toSet()
        return LinkChecker(BrandCatalog(SourceAssets), BadHostPrefixes { it in listed })
    }
}
