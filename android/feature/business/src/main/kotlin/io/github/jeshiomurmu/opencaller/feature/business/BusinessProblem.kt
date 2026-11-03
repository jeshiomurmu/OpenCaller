package io.github.jeshiomurmu.opencaller.feature.business

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessClaimRepository

/** What went wrong on a business screen, as the screen explains it. */
sealed interface BusinessProblem {
    /** No connection. */
    data object Offline : BusinessProblem

    /** The session ended; sign in again. */
    data object SignedOut : BusinessProblem

    /** This build has no server. */
    data object NotConnected : BusinessProblem

    /** The business number isn't a valid phone number. */
    data object InvalidNumber : BusinessProblem

    /** A field isn't accepted (names, website, …). */
    data object InvalidField : BusinessProblem

    /** The code is wrong. */
    data object WrongCode : BusinessProblem

    /** The code expired; ask for a new one. */
    data object CodeExpired : BusinessProblem

    /** Too many tries; [retryAfterSeconds] when the server said. */
    data class TooMany(
        val retryAfterSeconds: Long?,
    ) : BusinessProblem

    /** The number already belongs to a verified business. */
    data object AlreadyVerified : BusinessProblem

    /** Someone already has a claim in progress for the number. */
    data object ClaimOpen : BusinessProblem

    /** The file is too large. */
    data object FileTooLarge : BusinessProblem

    /** The file isn't a PDF, JPEG or PNG (a logo: PNG or JPEG). */
    data object FileType : BusinessProblem

    /** The file couldn't be read. */
    data object FileUnreadable : BusinessProblem

    /** No such business or claim. */
    data object NotFound : BusinessProblem

    /** Anything else the server refused. */
    data object Server : BusinessProblem
}

/** Maps an error from the claim API to what the screen shows. */
fun AppError.toBusinessProblem(): BusinessProblem =
    when (this) {
        AppError.Network -> BusinessProblem.Offline
        AppError.Auth -> BusinessProblem.SignedOut
        AppError.ServerNotConfigured -> BusinessProblem.NotConnected
        AppError.NotFound -> BusinessProblem.NotFound
        AppError.Storage, AppError.PermissionDenied -> BusinessProblem.FileUnreadable
        is AppError.RateLimited -> BusinessProblem.TooMany(retryAfterSeconds)
        is AppError.Validation -> validationProblem(message)
        else -> BusinessProblem.Server
    }

private fun validationProblem(code: String): BusinessProblem =
    when (code) {
        "invalid_phone_number" -> BusinessProblem.InvalidNumber
        "invalid_request" -> BusinessProblem.InvalidField
        "invalid_code" -> BusinessProblem.WrongCode
        "code_expired" -> BusinessProblem.CodeExpired
        "already_verified" -> BusinessProblem.AlreadyVerified
        "claim_open" -> BusinessProblem.ClaimOpen
        "claim_not_found" -> BusinessProblem.NotFound
        BusinessClaimRepository.FILE_TOO_LARGE -> BusinessProblem.FileTooLarge
        FILE_TYPE_ERROR -> BusinessProblem.FileType
        else -> BusinessProblem.Server
    }
