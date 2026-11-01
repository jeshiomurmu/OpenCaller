package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.data.RegisteredSender
import io.github.jeshiomurmu.opencaller.core.ml.assets.MlAssets
import io.github.jeshiomurmu.opencaller.core.ml.classify.RuleSmsClassifier
import io.github.jeshiomurmu.opencaller.core.ml.link.BadHostPrefixes
import io.github.jeshiomurmu.opencaller.core.ml.link.BrandCatalog
import io.github.jeshiomurmu.opencaller.core.ml.link.LinkChecker
import io.github.jeshiomurmu.opencaller.core.ml.otp.OtpExtractor
import io.github.jeshiomurmu.opencaller.core.ml.transaction.BankTemplates
import io.github.jeshiomurmu.opencaller.core.ml.transaction.TransactionParser
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.otp.CallActivityTracker
import io.github.jeshiomurmu.opencaller.core.telephony.otp.OtpAlarm
import io.github.jeshiomurmu.opencaller.core.telephony.otp.OtpWatch
import io.github.jeshiomurmu.opencaller.core.telephony.otp.RecentOtps
import io.github.jeshiomurmu.opencaller.core.telephony.otp.VerificationRanges

/** No bank templates and no brands: the tests here are about the pipeline, not `core:ml`'s data. */
internal object EmptyMlAssets : MlAssets {
    override fun read(name: String): String = if (name == BankTemplates.ASSET) """{"version":1,"banks":[]}""" else "{}"
}

/** A registry with what the test puts in. */
internal class FakeRegistry : SenderRegistry {
    val senders = mutableMapOf<Pair<String, String>, RegisteredSender>()
    val apps = mutableMapOf<String, String>()

    override suspend fun sender(
        countryIso: String,
        header: String,
    ): RegisteredSender? = senders[countryIso to header]

    override suspend fun appForHash(hash: String): String? = apps[hash]
}

/** Records the warnings. */
internal class RecordingAlarm : OtpAlarm {
    val duringCall = mutableListOf<Pair<String?, Boolean>>()
    val bursts = mutableListOf<Int>()

    override fun warnDuringCall(
        codeFrom: String?,
        flashCall: Boolean,
    ) {
        duringCall += codeFrom to flashCall
    }

    override fun warnBurst(codes: Int) {
        bursts += codes
    }
}

internal fun senderResolver(
    contacts: SenderNames,
    registry: SenderRegistry = FakeRegistry(),
    region: String = "US",
) = SenderResolver(contacts, registry, PhoneNumberNormalizer(), { region })

internal fun messageAnalyzer(badHosts: BadHostPrefixes = BadHostPrefixes { false }) =
    MessageAnalyzer(
        OtpExtractor(),
        TransactionParser(BankTemplates(EmptyMlAssets)),
        LinkChecker(BrandCatalog(EmptyMlAssets), badHosts),
        RuleSmsClassifier(),
    )

internal fun otpWatch(
    calls: CallActivityTracker,
    alarm: OtpAlarm,
    clock: Clock,
    recent: RecentOtps = RecentOtps { emptyList() },
    ranges: VerificationRanges = VerificationRanges { null },
) = OtpWatch(calls, recent, ranges, alarm, clock)
