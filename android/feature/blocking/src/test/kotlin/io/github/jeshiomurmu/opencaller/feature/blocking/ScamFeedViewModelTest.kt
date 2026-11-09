package io.github.jeshiomurmu.opencaller.feature.blocking

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.family.ScamFeedCards
import io.github.jeshiomurmu.opencaller.core.model.ScamCard
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Protection tab's scam cards (F-PR-15): the phone's country, the reader's language, hidden when unavailable. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScamFeedViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val asked = mutableListOf<Pair<String, String>>()
    private var answer: AppResult<ScamFeedCards> =
        AppResult.Success(ScamFeedCards(listOf(ScamCard("c1", "bn", "ভুয়া কুরিয়ার", "কোড দেবেন না।", null, 0L)), offline = true))

    private fun viewModel(region: String?) =
        ScamFeedViewModel(
            feed = { country, language ->
                asked += country to language
                answer
            },
            region = { region },
        )

    @Test
    fun theCardsOfThePhonesCountryAreShown() =
        runTest {
            val viewModel = viewModel("BD")
            viewModel.load("bn-BD")
            assertEquals(listOf("BD" to "bn-BD"), asked)
            assertEquals(
                listOf("c1"),
                viewModel.uiState.value.cards
                    .map { it.id },
            )
            assertTrue(viewModel.uiState.value.offline)
        }

    @Test
    fun withoutACountryOrAFeedNothingIsShown() =
        runTest {
            viewModel(null).also { it.load("en") }.let {
                assertTrue(
                    it.uiState.value.cards
                        .isEmpty(),
                )
            }
            assertTrue(asked.isEmpty())
            answer = AppResult.Failure(AppError.ServerNotConfigured)
            viewModel("US").also { it.load("en") }.let {
                assertTrue(
                    it.uiState.value.cards
                        .isEmpty(),
                )
            }
        }
}
