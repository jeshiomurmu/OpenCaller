package io.github.jeshiomurmu.opencaller.feature.business

import androidx.annotation.StringRes
import io.github.jeshiomurmu.opencaller.core.model.BusinessCategory
import io.github.jeshiomurmu.opencaller.core.model.ClaimStatus

/** Label of a business category. */
@StringRes
internal fun BusinessCategory.labelRes(): Int = CATEGORY_LABELS.getValue(this)

/** Where a claim stands, in words. */
@StringRes
internal fun ClaimStatus.labelRes(): Int =
    when (this) {
        ClaimStatus.VERIFYING -> R.string.claim_status_verifying
        ClaimStatus.VERIFIED -> R.string.claim_status_verified
        ClaimStatus.SUBMITTED -> R.string.claim_status_submitted
        ClaimStatus.APPROVED -> R.string.claim_status_approved
        ClaimStatus.REJECTED -> R.string.claim_status_rejected
        ClaimStatus.WITHDRAWN -> R.string.claim_status_withdrawn
    }

/** What the screen says about a problem. */
@StringRes
internal fun BusinessProblem.messageRes(): Int =
    if (this is BusinessProblem.TooMany) R.string.business_problem_too_many else PROBLEM_MESSAGES.getValue(this)

private val CATEGORY_LABELS =
    mapOf(
        BusinessCategory.BANK to R.string.business_category_bank,
        BusinessCategory.FINANCE to R.string.business_category_finance,
        BusinessCategory.INSURANCE to R.string.business_category_insurance,
        BusinessCategory.TELECOM to R.string.business_category_telecom,
        BusinessCategory.UTILITY to R.string.business_category_utility,
        BusinessCategory.GOVERNMENT to R.string.business_category_government,
        BusinessCategory.HEALTHCARE to R.string.business_category_healthcare,
        BusinessCategory.EDUCATION to R.string.business_category_education,
        BusinessCategory.RETAIL to R.string.business_category_retail,
        BusinessCategory.DELIVERY to R.string.business_category_delivery,
        BusinessCategory.TRAVEL to R.string.business_category_travel,
        BusinessCategory.TRANSPORT to R.string.business_category_transport,
        BusinessCategory.FOOD to R.string.business_category_food,
        BusinessCategory.SERVICES to R.string.business_category_services,
        BusinessCategory.OTHER to R.string.business_category_other,
    )

// Every problem but TooMany (which carries a value).
private val PROBLEM_MESSAGES: Map<BusinessProblem, Int> =
    mapOf(
        BusinessProblem.Offline to R.string.business_problem_offline,
        BusinessProblem.SignedOut to R.string.business_problem_signed_out,
        BusinessProblem.NotConnected to R.string.business_problem_not_connected,
        BusinessProblem.InvalidNumber to R.string.business_problem_invalid_number,
        BusinessProblem.InvalidField to R.string.business_problem_invalid_field,
        BusinessProblem.WrongCode to R.string.business_problem_wrong_code,
        BusinessProblem.CodeExpired to R.string.business_problem_code_expired,
        BusinessProblem.AlreadyVerified to R.string.business_problem_already_verified,
        BusinessProblem.ClaimOpen to R.string.business_problem_claim_open,
        BusinessProblem.FileTooLarge to R.string.business_problem_file_too_large,
        BusinessProblem.FileType to R.string.business_problem_file_type,
        BusinessProblem.FileUnreadable to R.string.business_problem_file_unreadable,
        BusinessProblem.NotFound to R.string.business_problem_not_found,
        BusinessProblem.Server to R.string.business_problem_server,
    )
