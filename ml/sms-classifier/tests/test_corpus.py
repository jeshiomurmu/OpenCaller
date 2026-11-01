import unicodedata

from sms_classifier import LABELS
from sms_classifier.corpus import kind, load
from sms_classifier.features import BUCKETS, fnv1a, normalise, vector
from sms_classifier.train import gate, macro_f1


def digits(text: str) -> str:
    return "".join(str(unicodedata.decimal(ch)) if ch.isdecimal() else ch for ch in text)


def test_the_corpus_has_every_label_in_every_split():
    for split in ("train", "val", "test"):
        assert {message.category for message in load(split)} == set(LABELS)


def test_at_least_300_messages_carry_a_code_across_three_countries():
    coded = [message for split in ("train", "val", "test") for message in load(split) if message.otp]
    assert len(coded) >= 300
    assert {message.country for message in coded} == {"BD", "IN", "US"}
    assert {message.lang for message in coded} == {"en", "bn", "hi"}


def test_every_code_is_in_its_text():
    for split in ("train", "val", "test"):
        for message in load(split):
            if message.otp:
                assert message.otp in digits(message.text), message.id
                assert 4 <= len(message.otp) <= 8, message.id


def test_a_template_lives_in_one_split_only():
    seen = {}
    for split in ("train", "val", "test"):
        for message in load(split):
            assert seen.setdefault(message.template, split) == split, message.template


def test_domain_bound_codes_are_on_the_last_line():
    for split in ("train", "val", "test"):
        for message in load(split):
            if message.origin:
                last = message.text.splitlines()[-1]
                assert last.startswith(f"@{message.origin} #{message.otp}"), message.id


def test_features_are_stable():
    # The app must reproduce these values (FEATURE_VERSION 1).
    assert fnv1a(b"") == 0x811C9DC5
    assert fnv1a(b"a") == 0xE40C292C
    assert normalise("  Your OTP  is ৪৮২৯১৩\n") == "your otp is 000000"
    features = vector("OTP 123")
    assert len(features) == BUCKETS
    assert sum(1 for value in features if value > 0) > 0


def test_the_generated_corpus_is_not_trained_on():
    problems = gate(load("train"), kind(), min_per_class=300, allow_synthetic=False)
    assert any("synthetic" in problem for problem in problems)
    assert any(problem.startswith("PERSONAL") for problem in problems)


def test_macro_f1():
    assert macro_f1(["OTP", "SPAM"], ["OTP", "SPAM"]) == 1.0
    assert macro_f1(["OTP", "SPAM"], ["OTP", "OTP"]) == (2 / 3 + 0) / 2
