"""A fixed OpenSSL fixture exercises the optional encrypted provider branch."""
import unittest

from zona_legacy_adapters import _alloha_decrypt_payload


class EncryptedProviderPayloadTests(unittest.TestCase):
    def test_encrypted_playlist_decodes_without_provider_credentials(self):
        # Generated independently with OpenSSL AES-256-CBC, MD5 and a test-only
        # password. This is test data, not an actual media URL or access secret.
        payload = (
            "#aCDwRpMo9iXZSiTgNjn7IQJ/fV2lurSI4AO61tQgT5zQMovbiYuqH9F800pACk5d"
            "AQ72GoP2weQOmlk2SRDCO7A=="
            "##00112233445566778899aabbccddeeff##0011223344556677"
        )
        value, error = _alloha_decrypt_payload(payload, "movia-fixture-password")
        self.assertIsNone(error)
        self.assertEqual(value, "[720p]https://media.example.test/movia-fixture.m3u8")


if __name__ == "__main__":
    unittest.main()
