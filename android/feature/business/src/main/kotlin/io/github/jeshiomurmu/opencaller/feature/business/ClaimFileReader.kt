package io.github.jeshiomurmu.opencaller.feature.business

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.business.ClaimFile
import io.github.jeshiomurmu.opencaller.core.model.ClaimFileKind

/** A file the user picked for a claim: its name (for the list) and what is uploaded. */
class PickedFile(
    val name: String,
    val file: ClaimFile,
)

/** Reads the files the user picks for a claim. */
interface ClaimFileReader {
    /** The document at content [uri] (PDF, JPEG or PNG, at most [ClaimFileKind.MAX_DOCUMENT_BYTES]). */
    suspend fun document(uri: String): AppResult<PickedFile>

    /** The picture at content [uri] as a logo: scaled down and re-encoded under [ClaimFileKind.MAX_LOGO_BYTES]. */
    suspend fun logo(uri: String): AppResult<PickedFile>
}

/** Error code (`AppError.Validation`) of a picked file of a type the server doesn't accept. */
const val FILE_TYPE_ERROR = "file_type"
