import os
import sys
from pathlib import Path

# Add backend directory to sys.path so 'app' imports work from any working directory
BACKEND_DIR = Path(__file__).resolve().parent.parent
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from app.api.endpoints import router as api_router

app = FastAPI(
    title="Adonis Job Copilot & Autofill API",
    description="Backend engine for AI-assisted job applications, resume curation, and autofill",
    version="1.0.0"
)

# Enable CORS for Chrome Extensions and local dashboards
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(api_router, prefix="/api")

# Mount Web Dashboard static assets
WEB_DIR = Path(__file__).resolve().parent.parent.parent / "web"
if WEB_DIR.exists():
    app.mount("/static", StaticFiles(directory=str(WEB_DIR)), name="static")

@app.get("/")
def index():
    index_file = WEB_DIR / "index.html"
    if index_file.exists():
        return FileResponse(str(index_file))
    return {
        "status": "online",
        "service": "Adonis Job Copilot Engine",
        "version": "1.0.0"
    }

@app.get("/dashboard")
def dashboard():
    return FileResponse(str(WEB_DIR / "index.html"))

@app.get("/dashboard.js")
def dashboard_js():
    return FileResponse(str(WEB_DIR / "dashboard.js"))

@app.get("/demo")
def demo_page():
    demo_file = Path(__file__).resolve().parent.parent.parent / "demo" / "sample_application.html"
    if demo_file.exists():
        return FileResponse(str(demo_file))
    return {"error": "Demo file not found"}

@app.get("/health")
def health_check():
    return {"status": "online", "version": "1.0.0"}


if __name__ == "__main__":
    import uvicorn
    from app.core.config import HOST, PORT
    uvicorn.run("app.main:app", host=HOST, port=PORT, reload=True)
