package io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms

import java.nio.charset.Charset

/** Character sets in MMS headers and text parts, identified by their IANA MIBenum. */
object MmsCharsets {
    /** "Any character set" (`*`): read as UTF-8. */
    const val ANY: Int = 0

    /** US-ASCII. */
    const val US_ASCII: Int = 3

    /** ISO-8859-1. */
    const val ISO_8859_1: Int = 4

    /** UTF-8, the character set OpenCaller writes. */
    const val UTF_8: Int = 106

    private val NAMES =
        mapOf(
            US_ASCII to "US-ASCII",
            ISO_8859_1 to "ISO-8859-1",
            5 to "ISO-8859-2",
            6 to "ISO-8859-3",
            7 to "ISO-8859-4",
            8 to "ISO-8859-5",
            9 to "ISO-8859-6",
            10 to "ISO-8859-7",
            11 to "ISO-8859-8",
            12 to "ISO-8859-9",
            17 to "Shift_JIS",
            18 to "EUC-JP",
            38 to "EUC-KR",
            39 to "ISO-2022-JP",
            UTF_8 to "UTF-8",
            113 to "GBK",
            114 to "GB18030",
            2025 to "GB2312",
            2026 to "Big5",
            1000 to "UTF-16BE",
            1015 to "UTF-16",
        )

    /** [bytes] decoded with the character set [mibEnum]; unknown or unsupported sets are read as UTF-8. */
    fun decode(
        bytes: ByteArray,
        mibEnum: Int,
    ): String = String(bytes, charsetOf(mibEnum))

    /** The MIBenum of a character set [name] such as `utf-8`, `null` when unknown. */
    fun mibEnumOf(name: String): Int? = NAMES.entries.firstOrNull { it.value.equals(name.trim(), ignoreCase = true) }?.key

    private fun charsetOf(mibEnum: Int): Charset = NAMES[mibEnum]?.takeIf(Charset::isSupported)?.let(Charset::forName) ?: Charsets.UTF_8
}

/** The well-known content types of WAP WSP (Assigned Numbers), by their short-integer code. */
internal object WellKnownContentTypes {
    private val TYPES =
        listOf(
            "*/*",
            "text/*",
            "text/html",
            "text/plain",
            "text/x-hdml",
            "text/x-ttml",
            "text/x-vCalendar",
            "text/x-vCard",
            "text/vnd.wap.wml",
            "text/vnd.wap.wmlscript",
            "text/vnd.wap.wta-event",
            "multipart/*",
            "multipart/mixed",
            "multipart/form-data",
            "multipart/byterantes",
            "multipart/alternative",
            "application/*",
            "application/java-vm",
            "application/x-www-form-urlencoded",
            "application/x-hdmlc",
            "application/vnd.wap.wmlc",
            "application/vnd.wap.wmlscriptc",
            "application/vnd.wap.wta-eventc",
            "application/vnd.wap.uaprof",
            "application/vnd.wap.wtls-ca-certificate",
            "application/vnd.wap.wtls-user-certificate",
            "application/x-x509-ca-cert",
            "application/x-x509-user-cert",
            "image/*",
            "image/gif",
            "image/jpeg",
            "image/tiff",
            "image/png",
            "image/vnd.wap.wbmp",
            "application/vnd.wap.multipart.*",
            "application/vnd.wap.multipart.mixed",
            "application/vnd.wap.multipart.form-data",
            "application/vnd.wap.multipart.byteranges",
            "application/vnd.wap.multipart.alternative",
            "application/xml",
            "text/xml",
            "application/vnd.wap.wbxml",
            "application/x-x968-cross-cert",
            "application/x-x968-ca-cert",
            "application/x-x968-user-cert",
            "text/vnd.wap.si",
            "application/vnd.wap.sic",
            "text/vnd.wap.sl",
            "application/vnd.wap.slc",
            "text/vnd.wap.co",
            "application/vnd.wap.coc",
            "application/vnd.wap.multipart.related",
            "application/vnd.wap.sia",
            "text/vnd.wap.connectivity-xml",
            "application/vnd.wap.connectivity-wbxml",
            "application/pkcs7-mime",
            "application/vnd.wap.hashed-certificate",
            "application/vnd.wap.signed-certificate",
            "application/vnd.wap.cert-response",
            "application/xhtml+xml",
            "application/wml+xml",
            "text/css",
            "application/vnd.wap.mms-message",
            "application/vnd.wap.rollover-certificate",
            "application/vnd.wap.locc+wbxml",
            "application/vnd.wap.loc+xml",
            "application/vnd.syncml.dm+wbxml",
            "application/vnd.syncml.dm+xml",
            "application/vnd.syncml.notification",
            "application/vnd.wap.xhtml+xml",
            "application/vnd.wv.csp.cir",
            "application/vnd.oma.dd+xml",
            "application/vnd.oma.drm.message",
            "application/vnd.oma.drm.content",
            "application/vnd.oma.drm.rights+xml",
            "application/vnd.oma.drm.rights+wbxml",
            "application/vnd.wv.csp+xml",
            "application/vnd.wv.csp+wbxml",
            "application/vnd.syncml.ds.notification",
            "audio/*",
            "video/*",
            "application/vnd.oma.dd2+xml",
            "application/mikey",
        )

    /** Multipart body, parts in a SMIL presentation. */
    const val MULTIPART_RELATED: String = "application/vnd.wap.multipart.related"

    /** Multipart body without a presentation. */
    const val MULTIPART_MIXED: String = "application/vnd.wap.multipart.mixed"

    /** The type with code [code], `null` when unassigned. */
    fun typeOf(code: Int): String? = TYPES.getOrNull(code)

    /** The code of [type], `null` when it isn't well known (then it is written as text). */
    fun codeOf(type: String): Int? = TYPES.indexOfFirst { it.equals(type, ignoreCase = true) }.takeIf { it >= 0 }
}
