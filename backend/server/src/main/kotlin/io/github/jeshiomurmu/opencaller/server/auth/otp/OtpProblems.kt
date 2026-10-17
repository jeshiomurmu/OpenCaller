package io.github.jeshiomurmu.opencaller.server.auth.otp

import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.HttpStatusCode

/** The 400 problem for a failed code check (sign-in and unlist), or `null` for a valid code. */
fun OtpCheck.toProblem(): ApiProblem? =
    when (this) {
        OtpCheck.Valid -> {
            null
        }

        is OtpCheck.Wrong -> {
            ApiProblem(HttpStatusCode.BadRequest, "invalid_code", "Wrong code", "$attemptsLeft attempts left.")
        }

        OtpCheck.Invalidated -> {
            ApiProblem(HttpStatusCode.BadRequest, "code_invalidated", "Too many wrong attempts", "Request a new code.")
        }

        OtpCheck.Missing -> {
            ApiProblem(HttpStatusCode.BadRequest, "code_expired", "No valid code for this number", "Request a new code.")
        }
    }

/** 400 `invalid_code_format`. */
fun invalidCodeFormat() = ApiProblem(HttpStatusCode.BadRequest, "invalid_code_format", "The code must be 6 digits")
