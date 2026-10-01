import copy
import json
from pathlib import Path
import random
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))
import zona_client_profile as profile
import zona_contract


def reference_checksum(raw, day):
    sum_a, sum_b = 0, 1
    for byte in raw:
        signed = byte if byte < 128 else byte - 256
        sum_b = (((signed + day) % 256) + sum_b) % profile.MODULUS
        sum_a = (sum_a + sum_b) % profile.MODULUS
    return (sum_a << 16) + sum_b


class OwnedProfileTests(unittest.TestCase):
    def test_matches_original_scan_for_every_byte_and_day_wrap(self):
        raw = bytes(range(256)) * 3
        value = profile.profile_for_bytes(raw)
        for day in (-1, 0, 1, 127, 128, 255, 256, 20_729, 1 << 40):
            with self.subTest(day=day):
                self.assertEqual(profile.checksum_for_day(value, day), reference_checksum(raw, day))

    def test_matches_original_scan_for_random_order_and_modulus_wrap(self):
        raw = random.Random(313).randbytes(100_003)
        value = profile.profile_for_bytes(raw)
        for day in (0, 2, 254, 20_727):
            self.assertEqual(profile.checksum_for_day(value, day), reference_checksum(raw, day))

    def test_rejects_unknown_fields_and_invalid_format(self):
        value = profile.profile_for_bytes(b"a")
        value["legacy_apk_path"] = "/outside/app.apk"
        with self.assertRaises(ValueError): profile.validate_profile(value)
        del value["legacy_apk_path"]
        value["format"] = "other"
        with self.assertRaises(ValueError): profile.validate_profile(value)

    def test_rejects_boolean_negative_short_and_oversized_vectors(self):
        original = profile.profile_for_bytes(b"a")
        for key, change in (("counts", [True]*256), ("counts", [-1]*256),
                            ("counts", [1]*255), ("position_weights", [65521]*256)):
            value = copy.deepcopy(original); value[key] = change
            with self.assertRaises(ValueError): profile.validate_profile(value)

    def test_rejects_inconsistent_size_counts_weights_and_digest(self):
        original = profile.profile_for_bytes(b"a")
        for key, change in (("artifact_bytes", 2), ("artifact_sha256", "../../x"),
                            ("artifact_bytes", True), ("position_weights", [0]*256)):
            value = copy.deepcopy(original); value[key] = change
            with self.assertRaises(ValueError): profile.validate_profile(value)

    def test_rejects_oversized_profile_before_parsing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "profile.json"
            path.write_bytes(b" " * (profile.MAX_PROFILE_BYTES + 1))
            profile.load_profile.cache_clear()
            try:
                with patch.object(profile, "PROFILE_PATH", path), self.assertRaises(ValueError):
                    profile.load_profile()
            finally: profile.load_profile.cache_clear()

    def test_cookie_preserves_long_shift_and_daily_cache_without_apk(self):
        value = profile.profile_for_bytes(b"\x00")
        client_time = (1 << 31) * 1000
        day = client_time // zona_contract._MILLIS_IN_DAY
        checksum = reference_checksum(b"\x00", day)
        expected = (1 << 63).to_bytes(8,"big").hex() + (checksum ^ (1 << 63)).to_bytes(8,"big").hex()
        zona_contract._COOKIE_CACHE.clear()
        with patch.object(zona_contract, "load_profile", return_value=value), \
             patch.object(zona_contract.secrets, "randbits", return_value=0) as random_bits:
            first = zona_contract._zona_cookie(client_time)
            second = zona_contract._zona_cookie(client_time + 1)
        self.assertEqual(first, expected)
        self.assertEqual(first, second)
        self.assertEqual(random_bits.call_count, 1)

    def test_missing_profile_does_not_fall_back_to_external_project(self):
        with patch.object(zona_contract, "load_profile", side_effect=FileNotFoundError):
            self.assertEqual(zona_contract._zona_cookie(1_700_000_000_000), "")
        source = Path(zona_contract.__file__).read_text()
        self.assertNotIn("zona-reference", source)
        self.assertNotIn("ZONA_APK_PATH", source)
        self.assertNotIn("legacy_apk_path", source)


if __name__ == "__main__": unittest.main()
