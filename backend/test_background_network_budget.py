import unittest
from unittest.mock import patch

import background_network_budget as b


class BackgroundNetworkBudgetTests(unittest.TestCase):
    def test_default_mobile_is_not_unmetered_even_if_local_wifi_exists(self):
        text = """
Active UID interfaces:
  iface=rmnet_data3 ident=[{type=0, metered=true, defaultNetwork=true, transports={0}}]
  iface=wlan2 ident=[{type=-1, metered=false, defaultNetwork=false, transports={1}}]
All wifi interfaces:
"""
        self.assertFalse(b.parse_default_unmetered(text))

    def test_default_unmetered_wifi_is_allowed_signal(self):
        text = """
Active UID interfaces:
  iface=wlan0 ident=[{type=-1, metered=false, defaultNetwork=true, transports={1}}]
All wifi interfaces:
"""
        self.assertTrue(b.parse_default_unmetered(text))

    def test_mobile_uid_parser_counts_physical_cellular_not_vpn(self):
        detail = """
  ident=[{type=0, metered=true, defaultNetwork=true, transports={0}}] uid=10736 set=FOREGROUND tag=0x0
    NetworkStatsHistory: bucketDuration=7200
      st=100 rb=1000 rp=1 tb=200 tp=1 op=0
      st=200 rb=3000 rp=1 tb=400 tp=1 op=0
  ident=[{type=17, metered=true, defaultNetwork=true, transports={0, 4}}] uid=10736 set=FOREGROUND tag=0x0
    NetworkStatsHistory: bucketDuration=7200
      st=200 rb=999999 rp=1 tb=999999 tp=1 op=0
  ident=[{type=0, metered=true, defaultNetwork=true, transports={0}}] uid=99999 set=DEFAULT tag=0x0
      st=200 rb=999999 rp=1 tb=999999 tp=1 op=0
"""
        self.assertEqual(b.parse_mobile_uid_bytes(detail, 10736, 150), 3400)

    def test_fail_closed_on_metered_default(self):
        netstats = """
Active UID interfaces:
  iface=rmnet_data3 ident=[{type=0, metered=true, defaultNetwork=true, transports={0}}]
All wifi interfaces:
"""
        battery = "AC powered: true\nstatus: 2\n"
        with patch.object(b, "_run_shell", side_effect=[netstats, battery]):
            decision = b.evaluate_live()
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason, "metered_default")

    def test_unmetered_and_charging_allows_without_quota_probe(self):
        netstats = """
Active UID interfaces:
  iface=wlan0 ident=[{type=-1, metered=false, defaultNetwork=true, transports={1}}]
All wifi interfaces:
"""
        battery = "USB powered: true\nstatus: 2\n"
        with patch.object(b, "_run_shell", side_effect=[netstats, battery]) as run:
            decision = b.evaluate_live()
        self.assertTrue(decision.allowed)
        self.assertEqual(decision.reason, "unmetered")
        self.assertEqual(run.call_count, 2)

    def test_metered_override_obeys_monthly_cap(self):
        netstats = """
Active UID interfaces:
  iface=rmnet_data3 ident=[{type=0, metered=true, defaultNetwork=true, transports={0}}]
All wifi interfaces:
"""
        battery = "AC powered: true\nstatus: 2\n"
        packages = "package:com.termux uid:10736\n"
        now = 2_000_000_000
        low = (
            "ident=[{type=0, metered=true}] uid=10736 set=FOREGROUND tag=0x0\n"
            " st=1 rb=10 rp=1 tb=10 tp=1 op=0\n"
        )
        high = (
            "ident=[{type=0, metered=true}] uid=10736 set=FOREGROUND tag=0x0\n"
            " st=1 rb=5000 rp=1 tb=5000 tp=1 op=0\n"
        )
        with patch.object(b, "_period_starts", return_value=(0, 0)), \
                patch.object(b, "_run_shell", side_effect=[netstats, battery, packages, low, high]):
            decision = b.evaluate_live(allow_metered=True, monthly_gib=0.000001, daily_mib=100)
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason, "monthly_mobile_budget_exhausted")


if __name__ == "__main__":
    unittest.main()
