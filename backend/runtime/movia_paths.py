import os
from pathlib import Path
DATA_DIR = Path(os.environ.get("MOVIA_DATA_DIR", str(Path(__file__).resolve().parent))).resolve()
DATA_DIR.mkdir(parents=True, exist_ok=True)
