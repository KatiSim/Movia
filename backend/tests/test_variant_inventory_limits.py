import unittest
from provider_contract import ProviderArticle, ProviderDefinition, ProviderRequest, VariantFolder, VariantStream, flatten_variant_tree

class VariantInventoryLimitsTests(unittest.TestCase):
    def setUp(self):
        self.article = ProviderArticle(ProviderDefinition(provider_id="movia:test", name="Test", family="movia"),"article","Example",2024,"article:1")
        self.request = ProviderRequest("42","Example",2024)
        self.tree = VariantFolder(children=tuple(VariantStream(url=f"https://cdn.example/{n}.mp4",voice=f"Voice {n}",quality="720p") for n in range(600)))
    def test_all_real_variants_survive_the_former_512_limit(self):
        self.assertEqual(600,len(flatten_variant_tree(self.article,self.tree,self.request)))
    def test_explicit_stream_budget_fails_instead_of_returning_partial_inventory(self):
        with self.assertRaisesRegex(ValueError,"VARIANT_LIMIT"):
            flatten_variant_tree(self.article,self.tree,self.request,max_streams=512)
    def test_node_budget_fails_instead_of_silently_truncating(self):
        with self.assertRaisesRegex(ValueError,"VARIANT_LIMIT"):
            flatten_variant_tree(self.article,self.tree,self.request,max_nodes=500)
