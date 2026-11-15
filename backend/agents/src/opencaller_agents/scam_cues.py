"""
Scam cues in what the other party said during a voice scan (F-PR-13, ADR 0011): phrases scam calls use, in English,
Bengali and Hindi (Devanagari and common romanised Hindi). Only the cue ids leave the agent; the transcript is
dropped. The Bengali and Hindi lists still need a native speaker's review (docs/phase.md, open issues).

A cue is a hint, never a verdict: the app shows "the caller mentioned ..." next to the voice result.
"""

from __future__ import annotations

import re
import unicodedata

# The ids the server accepts (ScanCues in the server, ScamCue in openapi.yaml, the app's strings).
CUE_IDS = (
    "otp_request",
    "payment_request",
    "gift_card",
    "remote_access",
    "urgency",
    "authority",
    "secrecy",
    "prize",
    "account_problem",
    "personal_data",
)

# English (and romanised Hindi): whole words, case-insensitive.
_LATIN: dict[str, str] = {
    "otp_request": (
        r"\b(otp|one[- ]time (password|passcode|code|pin)|verification code|security code|confirmation code"
        r"|(six|6|four|4)[- ]digit code|(read|tell|give) me the code|code (we|i) (just )?(sent|texted)"
        r"|otp (batao|bataiye|bolo|dijiye))\b"
    ),
    "payment_request": (
        r"\b(send (the |some )?money|transfer (the |some )?(money|amount|funds)|wire (the )?money"
        r"|(pay|deposit) (a|the) (fee|fine|charge|deposit)|processing fee|registration fee|security deposit"
        r"|pay (it )?now|upi|bkash|nagad|paytm|phonepe|google pay|paise bhej(o|iye|na))\b"
    ),
    "gift_card": r"\b(gift ?cards?|(itunes|google play|steam|amazon) cards?|voucher codes?)\b",
    "remote_access": (
        r"\b(anydesk|teamviewer|quick ?support|rustdesk|remote access|screen ?shar(e|ing)"
        r"|(install|download) (this|the|an|our) (app|application|software))\b"
    ),
    "urgency": (
        r"\b(right now|immediately|urgent(ly)?|as soon as possible|last chance|before it'?s too late|today only"
        r"|within (the next )?(\d+|one|two|five|ten|thirty|twenty[- ]four) (minutes?|hours?)|turant|jaldi)\b"
    ),
    "authority": (
        r"\b(police|cbi|fbi|customs|income tax|tax (department|office|authority)|arrest warrant|you will be arrested"
        r"|cyber ?(cell|crime)|narcotics|court (case|order|notice)|rbi|reserve bank|central bank|government officer)\b"
    ),
    "secrecy": (
        r"\b(don'?t tell (anyone|anybody|your (family|wife|husband|son|daughter|parents|bank))|keep (this|it) (a )?secret"
        r"|this is confidential|don'?t (hang|cut) up|stay on the line|do not disconnect|kisi ko mat bata(na|o|iye))\b"
    ),
    "prize": (
        r"\b(you('ve| have)? won|winner|lottery|jackpot|lucky draw|prize money|claim your (prize|reward)"
        r"|cash ?back offer|free gift)\b"
    ),
    "account_problem": (
        r"\b((account|card|sim|number) (has been|is|will be) (blocked|suspended|frozen|closed|locked|deactivated)"
        r"|kyc|unauthori[sz]ed (transaction|payment|login)|suspicious (activity|transaction|login)"
        r"|update your (details|kyc|account))\b"
    ),
    "personal_data": (
        r"\b((card|account|bank account|debit card|credit card) number|cvv|pin( number)?|password|passcode"
        r"|date of birth|mother'?s maiden name|aadhaar|aadhar|pan (card|number)|national id|nid number|social security)\b"
    ),
}

# Bengali and Hindi script: plain substrings (Python's \b splits words at the vowel signs of both scripts).
_SCRIPT: dict[str, tuple[str, ...]] = {
    "otp_request": (
        "ওটিপি",
        "কোডটা বলুন",
        "কোড বলুন",
        "কোডটি বলুন",
        "ভেরিফিকেশন কোড",
        "ओटीपी",
        "कोड बताइए",
        "कोड बताओ",
        "कोड बताएं",
        "वेरिफिकेशन कोड",
    ),
    "payment_request": (
        "টাকা পাঠান",
        "টাকা পাঠাতে",
        "বিকাশ",
        "নগদ",
        "পেমেন্ট করুন",
        "ট্রান্সফার করুন",
        "पैसे भेजो",
        "पैसे भेजिए",
        "पैसे भेजें",
        "पेमेंट करें",
        "पेमेंट कीजिए",
        "ट्रांसफर करें",
        "यूपीआई",
    ),
    "gift_card": ("গিফট কার্ড", "গিফ্ট কার্ড", "गिफ्ट कार्ड"),
    "remote_access": (
        "অ্যানিডেস্ক",
        "এনিডেস্ক",
        "টিমভিউয়ার",
        "স্ক্রিন শেয়ার",
        "অ্যাপটি ডাউনলোড",
        "অ্যাপ ডাউনলোড",
        "एनीडेस्क",
        "टीमव्यूअर",
        "स्क्रीन शेयर",
        "ऐप डाउनलोड",
        "एप डाउनलोड",
    ),
    "urgency": ("এখনই", "তাড়াতাড়ি", "জরুরি", "শেষ সুযোগ", "तुरंत", "अभी के अभी", "जल्दी कीजिए", "जल्दी करो", "आखिरी मौका"),
    "authority": (
        "পুলিশ",
        "কাস্টমস",
        "আদালত",
        "গ্রেফতার",
        "গ্রেপ্তার",
        "আয়কর",
        "বাংলাদেশ ব্যাংক",
        "पुलिस",
        "सीबीआई",
        "कस्टम",
        "इनकम टैक्स",
        "गिरफ्तार",
        "अदालत",
        "कोर्ट",
        "आरबीआई",
        "साइबर सेल",
    ),
    "secrecy": (
        "কাউকে বলবেন না",
        "কাউকে বলবে না",
        "গোপন রাখুন",
        "ফোন কাটবেন না",
        "लाइन मत काटिए",
        "फोन मत काटिए",
        "फोन मत काटना",
        "किसी को मत बताना",
        "किसी को मत बताइए",
        "किसी को न बताएं",
        "गुप्त रखें",
    ),
    "prize": ("লটারি", "পুরস্কার", "জিতেছেন", "ক্যাশব্যাক", "लॉटरी", "इनाम", "जीत लिया", "जीता है", "कैशबैक"),
    "account_problem": (
        "অ্যাকাউন্ট বন্ধ",
        "অ্যাকাউন্ট ব্লক",
        "কার্ড ব্লক",
        "সিম ব্লক",
        "কেওয়াইসি",
        "खाता बंद",
        "अकाउंट बंद",
        "अकाउंट ब्लॉक",
        "कार्ड ब्लॉक",
        "सिम ब्लॉक",
        "केवाईसी",
    ),
    "personal_data": (
        "পাসওয়ার্ড",
        "পিন নম্বর",
        "কার্ড নম্বর",
        "অ্যাকাউন্ট নম্বর",
        "এনআইডি",
        "জাতীয় পরিচয়পত্র",
        "জন্ম তারিখ",
        "पासवर्ड",
        "पिन नंबर",
        "कार्ड नंबर",
        "खाता नंबर",
        "अकाउंट नंबर",
        "आधार",
        "पैन कार्ड",
        "सीवीवी",
        "जन्म तिथि",
    ),
}

_COMPILED = {cue: re.compile(pattern, re.IGNORECASE) for cue, pattern in _LATIN.items()}
_FOLDED = {cue: tuple(unicodedata.normalize("NFC", phrase) for phrase in phrases) for cue, phrases in _SCRIPT.items()}


def find_cues(text: str) -> list[str]:
    """The cue ids found in [text], in the order of [CUE_IDS]; empty for empty text."""
    if not text.strip():
        return []
    normalized = unicodedata.normalize("NFC", text).replace("’", "'")
    return [
        cue
        for cue in CUE_IDS
        if _COMPILED[cue].search(normalized) or any(phrase in normalized for phrase in _FOLDED[cue])
    ]
