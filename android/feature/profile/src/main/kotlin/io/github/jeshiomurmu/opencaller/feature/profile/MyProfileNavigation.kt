package io.github.jeshiomurmu.opencaller.feature.profile

import io.github.jeshiomurmu.opencaller.core.model.VisitKind

/** Where the profile screen leads. */
data class MyProfileNavigation(
    val onOpenVisitors: (VisitKind) -> Unit,
    val onOpenRequests: () -> Unit,
    val onDeleted: () -> Unit,
)
