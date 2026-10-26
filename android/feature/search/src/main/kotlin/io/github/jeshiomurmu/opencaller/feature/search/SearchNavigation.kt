package io.github.jeshiomurmu.opencaller.feature.search

/** What the search screen can open elsewhere. */
data class SearchNavigation(
    val onSignIn: () -> Unit,
    val onDispute: (e164: String) -> Unit,
)
