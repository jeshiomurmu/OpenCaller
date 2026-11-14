"""Scam cues in English, Bengali and Hindi (ADR 0011); the Bengali and Hindi phrases await a native speaker's review."""

import pytest

from opencaller_agents.scam_cues import CUE_IDS, find_cues


@pytest.mark.parametrize(
    ("text", "cues"),
    [
        ("Please tell me the six digit code we just sent you.", ["otp_request"]),
        ("Transfer the money by bKash within 30 minutes.", ["payment_request", "urgency"]),
        ("Buy Google Play cards and read me the numbers.", ["gift_card"]),
        ("Install this app called AnyDesk so I can help.", ["remote_access"]),
        ("This is the cyber crime cell, there is an arrest warrant.", ["authority"]),
        ("Your account has been blocked, we need your card number and CVV.", ["account_problem", "personal_data"]),
        ("Congratulations, you've won the lucky draw!", ["prize"]),
        ("Don’t tell anyone, keep this a secret.", ["secrecy"]),
        ("Hi, it's me, are we still meeting for lunch tomorrow?", []),
        ("", []),
        # Bengali
        ("আপনার অ্যাকাউন্ট বন্ধ হয়ে যাবে, এখনই ওটিপি কোডটা বলুন", ["otp_request", "urgency", "account_problem"]),
        ("আপনি লটারি জিতেছেন, বিকাশে টাকা পাঠান", ["payment_request", "prize"]),
        # Hindi (Devanagari and romanised)
        ("मैं पुलिस से बोल रहा हूँ, किसी को मत बताना", ["authority", "secrecy"]),
        ("आपका केवाईसी अपडेट करना है, आधार और पैन कार्ड नंबर बताइए", ["account_problem", "personal_data"]),
        ("sir otp batao jaldi", ["otp_request", "urgency"]),
    ],
)
def test_cues(text: str, cues: list[str]) -> None:
    assert find_cues(text) == cues


def test_the_ids_are_the_servers() -> None:
    # Keep in step with ScanCues (server), ScamCue (openapi.yaml) and the app's strings.
    assert CUE_IDS == (
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


def test_words_inside_other_words_dont_count() -> None:
    assert find_cues("The topic was spinning tops and the pinball machine.") == []
