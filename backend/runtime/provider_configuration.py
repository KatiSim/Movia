"""Movia-owned domain data. No old app's configuration service is contacted."""
import json
from pathlib import Path

def configuration():
    raw = (Path(__file__).with_name("providers.json")).read_bytes()
    if len(raw) > 65536:
        raise ValueError("provider_configuration_too_large")
    data = json.loads(raw)
    if data.get("schemaVersion") != 1 or not isinstance(data.get("providers"), list):
        raise ValueError("provider_configuration_schema")
    from cloud_api import public_locator
    active = {1,4,13,16,17,21,23,28,29,31,32,33}
    seen = set()
    for row in data["providers"]:
        ident = row.get("id")
        if type(ident) is not int or ident not in active or ident in seen:
            raise ValueError("provider_configuration_id")
        seen.add(ident)
        urls = [row["base"]] if row.get("base") else []
        urls.extend(row.get("aliases", []))
        if any(not public_locator(url) for url in urls):
            raise ValueError("provider_configuration_domain")
    props = data.get("properties", {})
    if any(key != "rezka_s" or not public_locator(value) for key,value in props.items()):
        raise ValueError("provider_configuration_property")
    return data
