package io.github.jeshiomurmu.opencaller.core.telephony.providers

import android.content.ContentProviderResult
import android.content.ContentUris
import android.os.Build
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import io.github.jeshiomurmu.opencaller.core.model.ContactDraft
import io.github.jeshiomurmu.opencaller.core.model.ContactPhoneDraft
import io.github.jeshiomurmu.opencaller.core.model.PhoneLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Creating and editing a contact as one ContactsProvider batch (docs/prd.md F-CM-04). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q, Build.VERSION_CODES.VANILLA_ICE_CREAM])
class ContactWritesTest {
    private val draft =
        ContactDraft(
            name = "  Ram Kumar ",
            phones =
                listOf(
                    ContactPhoneDraft("+8801712345678", PhoneLabel.MOBILE),
                    ContactPhoneDraft(" "),
                    ContactPhoneDraft("029876543", PhoneLabel.WORK),
                ),
            emails = listOf("ram@example.com", ""),
        )

    @Test
    fun aNewContactIsARawContactOnThePhoneWithItsRows() {
        val operations = ContactWrites.operations(rawContactId = null, draft = draft)
        assertEquals(5, operations.size)
        val raw = operations[0]
        assertTrue(raw.isInsert)
        assertEquals(RawContacts.CONTENT_URI, raw.uri)
        val rawValues = raw.resolveValueBackReferences(emptyArray(), 0)
        assertTrue(rawValues.containsKey(RawContacts.ACCOUNT_TYPE) && rawValues.getAsString(RawContacts.ACCOUNT_TYPE) == null)

        // Every data row points at the new raw contact (back reference to operation 0).
        val created = arrayOf(ContentProviderResult(ContentUris.withAppendedId(RawContacts.CONTENT_URI, 42)))
        val rows = operations.drop(1).map { it.resolveValueBackReferences(created, 1) }
        rows.forEach { assertEquals(42L, it.getAsLong(Data.RAW_CONTACT_ID)) }
        assertEquals(
            listOf(
                StructuredName.CONTENT_ITEM_TYPE,
                Phone.CONTENT_ITEM_TYPE,
                Phone.CONTENT_ITEM_TYPE,
                Email.CONTENT_ITEM_TYPE,
            ),
            rows.map {
                it.getAsString(Data.MIMETYPE)
            },
        )
        assertEquals("Ram Kumar", rows[0].getAsString(StructuredName.DISPLAY_NAME))
        assertEquals("+8801712345678", rows[1].getAsString(Phone.NUMBER))
        assertEquals(Phone.TYPE_MOBILE, rows[1].getAsInteger(Phone.TYPE))
        assertEquals(Phone.TYPE_WORK, rows[2].getAsInteger(Phone.TYPE))
        assertEquals("ram@example.com", rows[3].getAsString(Email.ADDRESS))
    }

    @Test
    fun anEditReplacesTheRowsOfThatRawContact() {
        val operations = ContactWrites.operations(rawContactId = 7, draft = draft.copy(emails = emptyList()))
        assertEquals(4, operations.size)
        assertTrue(operations[0].isDelete)
        assertEquals(Data.CONTENT_URI, operations[0].uri)
        val rows = operations.drop(1).map { it.resolveValueBackReferences(emptyArray(), 0) }
        rows.forEach { assertEquals(7L, it.getAsLong(Data.RAW_CONTACT_ID)) }
        assertEquals(
            listOf(StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE),
            rows.map {
                it.getAsString(Data.MIMETYPE)
            },
        )
    }

    @Test
    fun labelsRoundTrip() {
        PhoneLabel.entries.forEach { assertEquals(it, phoneLabelOf(phoneTypeOf(it))) }
        assertEquals(PhoneLabel.OTHER, phoneLabelOf(Phone.TYPE_PAGER))
    }
}
