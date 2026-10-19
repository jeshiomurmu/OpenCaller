package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** What the sign-in screen says about [problem]. */
@Composable
internal fun problemText(problem: SignInProblem): String =
    when (problem) {
        SignInProblem.InvalidNumber -> {
            stringResource(R.string.sign_in_problem_number)
        }

        SignInProblem.WrongCode -> {
            stringResource(R.string.sign_in_problem_wrong_code)
        }

        SignInProblem.CodeFormat -> {
            stringResource(R.string.sign_in_problem_code_format)
        }

        SignInProblem.CodeExpired -> {
            stringResource(R.string.sign_in_problem_expired)
        }

        is SignInProblem.TooMany -> {
            val minutes = problem.retryAfterSeconds?.let(::minutesFor)
            if (minutes == null) {
                stringResource(R.string.sign_in_problem_too_many)
            } else {
                pluralStringResource(R.plurals.sign_in_problem_too_many_wait, minutes, minutes)
            }
        }

        SignInProblem.Offline -> {
            stringResource(R.string.problem_offline)
        }

        SignInProblem.NotConnected -> {
            stringResource(R.string.not_connected_title)
        }

        SignInProblem.ServerError -> {
            stringResource(R.string.problem_server)
        }
    }

/** Whole minutes for [seconds], rounded up, at least 1. */
internal fun minutesFor(seconds: Long): Int = ((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt().coerceAtLeast(1)

private const val SECONDS_PER_MINUTE = 60L
