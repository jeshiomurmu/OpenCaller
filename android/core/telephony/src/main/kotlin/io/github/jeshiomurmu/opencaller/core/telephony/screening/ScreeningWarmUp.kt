package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import javax.inject.Inject

/**
 * Prepares the screening path before the first real call: one dry decision for an example number of the user's
 * country loads that country's numbering metadata, opens the encrypted database and runs every lookup once, so the
 * first call is decided as fast as the others (docs/implementation_plan.md Phase 3: p95 < 50 ms). Nothing is logged
 * or shown. Run it in the background at app start and whenever the home region changes.
 */
class ScreeningWarmUp
    @Inject
    constructor(
        private val engine: ScreeningEngine,
        private val normalizer: PhoneNumberNormalizer,
        private val homeRegion: HomeRegionProvider,
    ) {
        /** Returns the decision of the dry run, or `null` when the home region is unknown. */
        suspend fun warmUp(): ScreeningResult? =
            homeRegion
                .homeRegion()
                ?.let(normalizer::exampleNumber)
                ?.let { engine.screen(ScreeningCall(it.e164, Direction.INCOMING)) }
    }
