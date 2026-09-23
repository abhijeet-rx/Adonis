#!/usr/bin/env python3
"""
Adonis Launcher Script
Run this script from the project root: python start.py
"""
import sys
from pathlib import Path

# Safe encoding configuration for Windows terminals
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# Add backend to sys.path
ROOT_DIR = Path(__file__).resolve().parent
BACKEND_DIR = ROOT_DIR / "backend"

if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

try:
    import uvicorn
    from app.main import app
    from app.core.config import HOST, PORT
except ImportError as e:
    print("\n[ERROR] Failed to load Adonis dependencies:", e)
    print("\nPlease ensure dependencies are installed:")
    print("  pip install -r backend/requirements.txt")
    print("Or activate the virtual environment:")
    print("  backend\\.venv\\Scripts\\activate\n")
    sys.exit(1)

if __name__ == "__main__":
    print("=" * 60)
    print("  [*] ADONIS AI JOB APPLICATION COPILOT & AUTOFILTER")
    print("=" * 60)
    print(f"  -> Web Dashboard:      http://{HOST}:{PORT}")
    print(f"  -> Demo ATS Job Page:  http://{HOST}:{PORT}/demo")
    print(f"  -> API Documentation:  http://{HOST}:{PORT}/docs")
    print("=" * 60)
    print("Starting server... Press CTRL+C to stop.\n")
    uvicorn.run("app.main:app", host=HOST, port=PORT, reload=True, app_dir=str(BACKEND_DIR))
