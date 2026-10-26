package io.github.jeshiomurmu.opencaller.core.telephony.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.os.RemoteException
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.ContactDetail
import io.github.jeshiomurmu.opencaller.core.model.ContactDraft
import io.github.jeshiomurmu.opencaller.core.model.ContactPhone
import io.github.jeshiomurmu.opencaller.core.model.ContactSummary
import io.github.jeshiomurmu.opencaller.core.model.PhoneLabel
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's contacts in Android's contacts provider. OpenCaller reads and edits them on the device and never syncs
 * or uploads them (docs/prd.md F-CM-04, docs/architecture.md §10).
 */
interface ContactsSource {
    /** True when the contacts permission is granted. */
    fun canRead(): Boolean

    /** True when OpenCaller may create and change contacts. */
    fun canWrite(): Boolean

    /** Every contact with a name, sorted by name, again after every change. */
    fun observeAll(): Flow<AppResult<List<ContactSummary>>>

    /** The contact with [contactId], or `null` once it is gone, again after every change. */
    fun observe(contactId: Long): Flow<AppResult<ContactDetail?>>

    /**
     * Saves [draft]: as a new contact on the phone when [rawContactId] is `null`, else into that raw contact
     * (its name, numbers and e-mail addresses are replaced). Returns the contact's id.
     */
    suspend fun save(
        rawContactId: Long?,
        draft: ContactDraft,
    ): AppResult<Long>

    /** Adds the contact to the favourites, or removes it. */
    suspend fun setStarred(
        contactId: Long,
        starred: Boolean,
    ): AppResult<Unit>
}

/** [ContactsSource] backed by `ContactsContract`. */
@Suppress("TooManyFunctions") // Reads, writes and their row mapping, all against one provider.
@Singleton
class DeviceContacts
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val normalizer: PhoneNumberNormalizer,
        private val region: HomeRegionProvider,
        private val dispatchers: DispatcherProvider,
    ) : ContactsSource {
        override fun canRead(): Boolean = granted(Manifest.permission.READ_CONTACTS)

        override fun canWrite(): Boolean = granted(Manifest.permission.WRITE_CONTACTS)

        override fun observeAll(): Flow<AppResult<List<ContactSummary>>> =
            context.contentResolver
                .changes(Contacts.CONTENT_URI)
                .map { if (canRead()) guarded("list") { readAll() } else AppResult.Failure(AppError.PermissionDenied) }
                .flowOn(dispatchers.io)

        override fun observe(contactId: Long): Flow<AppResult<ContactDetail?>> =
            context.contentResolver
                .changes(Contacts.CONTENT_URI)
                .map { if (canRead()) guarded("detail") { readDetail(contactId) } else AppResult.Failure(AppError.PermissionDenied) }
                .flowOn(dispatchers.io)

        override suspend fun save(
            rawContactId: Long?,
            draft: ContactDraft,
        ): AppResult<Long> =
            when {
                !canWrite() -> AppResult.Failure(AppError.PermissionDenied)
                !draft.canSave -> AppResult.Failure(AppError.Validation(EMPTY_CONTACT))
                else -> withContext(dispatchers.io) { guarded("save") { write(rawContactId, draft) } }
            }

        // canWrite() was checked by the caller paths above; a revoked permission is caught as SecurityException.
        @SuppressLint("MissingPermission")
        override suspend fun setStarred(
            contactId: Long,
            starred: Boolean,
        ): AppResult<Unit> {
            if (!canWrite()) return AppResult.Failure(AppError.PermissionDenied)
            return withContext(dispatchers.io) {
                guarded("favourite") {
                    val values = ContentValues().apply { put(Contacts.STARRED, if (starred) 1 else 0) }
                    context.contentResolver.update(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), values, null, null)
                    Unit
                }
            }
        }

        // READ_CONTACTS is checked before each read (canRead); a revoked permission is caught as SecurityException.
        @SuppressLint("MissingPermission")
        private fun readAll(): List<ContactSummary> {
            val home = region.homeRegion() ?: UNKNOWN_REGION
            val phones = mutableMapOf<Long, MutableList<ContactPhone>>()
            val phoneColumns = arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.TYPE)
            context.contentResolver.query(Phone.CONTENT_URI, phoneColumns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    phoneOf(c.getString(1), c.getInt(2), home)?.let { phones.getOrPut(c.getLong(0)) { mutableListOf() }.add(it) }
                }
            }
            val projection = arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.STARRED)
            return context.contentResolver
                .query(Contacts.CONTENT_URI, projection, null, null, SORT_BY_NAME)
                ?.use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val name = c.getString(2)?.takeIf(String::isNotBlank) ?: continue
                            val id = c.getLong(0)
                            add(
                                ContactSummary(
                                    id,
                                    c.getString(1).orEmpty(),
                                    name,
                                    c.getInt(c.getColumnIndexOrThrow(Contacts.STARRED)) == 1,
                                    phones[id].orEmpty().distinctBy { it.dialString },
                                ),
                            )
                        }
                    }
                }.orEmpty()
        }

        @SuppressLint("MissingPermission")
        private fun readDetail(contactId: Long): ContactDetail? {
            val uri = ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId)
            val projection = arrayOf(Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.STARRED)
            val contact =
                context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                    if (c.moveToFirst()) Triple(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getInt(2) == 1) else null
                } ?: return null
            val home = region.homeRegion() ?: UNKNOWN_REGION
            val phones = mutableListOf<ContactPhone>()
            val emails = mutableListOf<String>()
            val selection = "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (?, ?)"
            val args = arrayOf(contactId.toString(), Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE)
            val columns = arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA2)
            context.contentResolver.query(Data.CONTENT_URI, columns, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(0) == Phone.CONTENT_ITEM_TYPE) {
                        phoneOf(c.getString(1), c.getInt(2), home)?.let(phones::add)
                    } else {
                        c.getString(1)?.takeIf(String::isNotBlank)?.let(emails::add)
                    }
                }
            }
            return ContactDetail(
                id = contactId,
                lookupKey = contact.first,
                name = contact.second,
                starred = contact.third,
                phones = phones.distinctBy { it.dialString },
                emails = emails.distinct(),
                rawContactId = rawContactOf(contactId),
            )
        }

        @SuppressLint("MissingPermission")
        private fun rawContactOf(contactId: Long): Long? =
            context.contentResolver
                .query(
                    RawContacts.CONTENT_URI,
                    arrayOf(RawContacts._ID),
                    "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
                    arrayOf(contactId.toString()),
                    null,
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        // One batch, so a contact is saved whole or not at all.
        @SuppressLint("MissingPermission")
        private fun write(
            rawContactId: Long?,
            draft: ContactDraft,
        ): Long {
            val operations = ContactWrites.operations(rawContactId, draft)
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
            val rawId = rawContactId ?: results.firstOrNull()?.uri?.let(ContentUris::parseId) ?: error("No raw contact created")
            return context.contentResolver
                .query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.CONTACT_ID), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                ?: error("The new contact wasn't found")
        }

        private fun phoneOf(
            stored: String?,
            type: Int,
            home: String,
        ): ContactPhone? {
            val dialString = stored?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return ContactPhone(normalizer.fromHandle(dialString, home), dialString, phoneLabelOf(type))
        }

        private fun granted(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        // Errors at the provider boundary become AppErrors (docs/rules.md §5).
        private fun <T> guarded(
            what: String,
            block: () -> T,
        ): AppResult<T> =
            try {
                AppResult.Success(block())
            } catch (e: SecurityException) {
                Timber.w(e, "Contacts %s not allowed", what)
                AppResult.Failure(AppError.PermissionDenied)
            } catch (e: SQLiteException) {
                storageFailure(what, e)
            } catch (e: OperationApplicationException) {
                storageFailure(what, e)
            } catch (e: RemoteException) {
                storageFailure(what, e)
            } catch (e: IllegalArgumentException) {
                storageFailure(what, e)
            } catch (e: IllegalStateException) {
                storageFailure(what, e)
            }

        private fun storageFailure(
            what: String,
            e: Exception,
        ): AppResult.Failure {
            Timber.w(e, "Contacts %s failed", what)
            return AppResult.Failure(AppError.Storage)
        }

        /** Reasons in [AppError.Validation]. */
        companion object {
            /** Neither a name nor a number. */
            const val EMPTY_CONTACT = "empty_contact"

            private const val UNKNOWN_REGION = "ZZ"
            private const val SORT_BY_NAME = "${Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"
        }
    }

/** The provider operations that save a contact (internal for tests). */
internal object ContactWrites {
    /**
     * Operations for [draft]: a new raw contact on the phone (no account) when [rawContactId] is `null`, else the
     * replacement of that raw contact's name, numbers and e-mail addresses.
     */
    fun operations(
        rawContactId: Long?,
        draft: ContactDraft,
    ): ArrayList<ContentProviderOperation> {
        val operations = arrayListOf(if (rawContactId == null) newRawContact() else clearRows(rawContactId))
        dataRows(draft).forEach { (mimeType, values) ->
            val insert = ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.MIMETYPE, mimeType)
            // A new contact's rows point at the raw contact inserted by operation 0.
            if (rawContactId == null) {
                insert.withValueBackReference(Data.RAW_CONTACT_ID, 0)
            } else {
                insert.withValue(Data.RAW_CONTACT_ID, rawContactId)
            }
            values.forEach { (column, value) -> insert.withValue(column, value) }
            operations += insert.build()
        }
        return operations
    }

    // A raw contact stored on the phone only (no account): never synced anywhere by OpenCaller.
    private fun newRawContact(): ContentProviderOperation =
        ContentProviderOperation
            .newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, null)
            .withValue(RawContacts.ACCOUNT_NAME, null)
            .build()

    // The raw contact's name, numbers and e-mail addresses, replaced by the edit; other rows (photo, notes) stay.
    private fun clearRows(rawContactId: Long): ContentProviderOperation =
        ContentProviderOperation
            .newDelete(Data.CONTENT_URI)
            .withSelection(
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (?, ?, ?)",
                arrayOf(rawContactId.toString(), StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE),
            ).build()

    // Mime type and values of each data row of [draft].
    private fun dataRows(draft: ContactDraft): List<Pair<String, Map<String, Any>>> =
        buildList {
            draft.name.trim().takeIf(String::isNotEmpty)?.let {
                add(StructuredName.CONTENT_ITEM_TYPE to mapOf(StructuredName.DISPLAY_NAME to it))
            }
            draft.filledPhones.forEach { phone ->
                add(Phone.CONTENT_ITEM_TYPE to mapOf(Phone.NUMBER to phone.dialString.trim(), Phone.TYPE to phoneTypeOf(phone.label)))
            }
            draft.filledEmails.forEach { add(Email.CONTENT_ITEM_TYPE to mapOf(Email.ADDRESS to it, Email.TYPE to Email.TYPE_OTHER)) }
        }
}

/** `Phone.TYPE` to [PhoneLabel]. */
internal fun phoneLabelOf(type: Int): PhoneLabel =
    when (type) {
        Phone.TYPE_MOBILE -> PhoneLabel.MOBILE
        Phone.TYPE_HOME -> PhoneLabel.HOME
        Phone.TYPE_WORK -> PhoneLabel.WORK
        Phone.TYPE_MAIN -> PhoneLabel.MAIN
        else -> PhoneLabel.OTHER
    }

/** [PhoneLabel] to `Phone.TYPE`. */
internal fun phoneTypeOf(label: PhoneLabel): Int =
    when (label) {
        PhoneLabel.MOBILE -> Phone.TYPE_MOBILE
        PhoneLabel.HOME -> Phone.TYPE_HOME
        PhoneLabel.WORK -> Phone.TYPE_WORK
        PhoneLabel.MAIN -> Phone.TYPE_MAIN
        PhoneLabel.OTHER -> Phone.TYPE_OTHER
    }
