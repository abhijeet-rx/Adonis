# ⚡ Adonis — Autonomous AI Job Application Filler & ATS Resume Curator

> **Adonis** is an AI-powered job application copilot that ingests your candidate profile, curates your skills and resume dynamically for each job description, and autofills job applications across ATS portals (Greenhouse, Lever, Ashby, Workday, and custom forms).

---

## 🌟 Core Features

- 📑 **Multi-Format Resume Ingestion**: Upload your base resume in **PDF**, **Microsoft Word (.docx)**, or **Image (.png, .jpg)** format. Adonis parses it into a structured Candidate Vault.
- 🎯 **Dynamic AI Resume & Skills Curation**:
  - Extracts key technical requirements, seniority, and ATS keywords from any job listing.
  - Automatically re-ranks and highlights matching skills.
  - Calculates real-time **ATS Match Score (%)** and highlights missing keywords.
  - Dynamically crafts tailored headlines, executive summaries, and company-specific cover letters.
- 📄 **ATS-Compliant Document Generation**: One-click download of tailored **PDF** and **DOCX** resumes formatted specifically to pass automated applicant tracking systems.
- 🧩 **Chrome Extension Autopilot**:
  - Seamlessly detects active job postings directly in Chrome, Edge, Brave, or Arc.
  - One-click **⚡ Autofill Application Form** for Greenhouse, Lever, Ashby, and standard web forms.
  - Form field auto-detection for personal info, links, demographics, salary, notice period, and custom screening questions.
- 🛡️ **Autofill Only Policy (Zero Auto-Submit)**:
  - Adonis populates fields and highlights them in glowing green for visual verification.
  - The final review and submission remain 100% in your hands.
- 📊 **Application Tracker**: Keeps an automated history of all applications filled and match scores.

---

## 🏗️ Architecture

```
adonis/
├── backend/            # Python FastAPI AI & Data Core
│   ├── app/
│   │   ├── api/        # REST endpoints (profile, job analyze, curate, export)
│   │   ├── core/       # Configuration & directories
│   │   ├── models/     # Pydantic models (Profile, JobAnalysis, CuratedResume)
│   │   ├── services/   # Parser (PDF/DOCX/Image), AI Service, Resume Exporter
│   │   └── main.py     # FastAPI application entrypoint
│   ├── requirements.txt
│   └── tests/          # Automated test suite (Pytest)
├── extension/          # Manifest V3 Chrome Extension
│   ├── manifest.json
│   ├── sidepanel.html  # Modern AI Copilot sidepanel UI
│   ├── sidepanel.js    # Sidepanel logic & API bridge
│   ├── content.js      # DOM Auto-filler & ATS adapters
│   └── content.css     # Field highlighting & completion toast
├── web/                # Web Dashboard
│   ├── index.html      # Responsive Profile & Resume Studio UI
│   └── dashboard.js    # Ingestion, curation, and export handling
└── demo/
    └── sample_application.html # Sandbox ATS form for testing
```

---

## 🚀 Quickstart Guide

### 1. Prerequisites
- **Python 3.10+** (Python 3.12 recommended)
- **Google Chrome** (or any Chromium browser: Brave, Edge, Arc)

### 2. Backend & Dashboard Setup

1. **Activate the virtual environment & install dependencies**:
   ```bash
   # Windows PowerShell
   backend\.venv\Scripts\activate
   pip install -r backend\requirements.txt
   ```

2. **Configure Environment Variables (Optional)**:
   Create a `.env` file in the root or `backend/` directory:
   ```env
   GEMINI_API_KEY=your_gemini_api_key_here
   PORT=8000
   ```
   *(Note: Adonis includes an intelligent local fallback engine, so it works fully out of the box even without an API key!)*

3. **Start the Adonis Server**:
   ```bash
   backend\.venv\Scripts\python backend\app\main.py
   ```
   - **Web Dashboard**: [http://localhost:8000](http://localhost:8000)
   - **Interactive API Docs**: [http://localhost:8000/docs](http://localhost:8000/docs)
   - **Demo ATS Job Form**: [http://localhost:8000/demo](http://localhost:8000/demo)

---

### 3. Chrome Extension Setup

1. Open your browser and navigate to `chrome://extensions/`.
2. Toggle on **Developer mode** in the top-right corner.
3. Click **Load unpacked**.
4. Select the `extension/` folder located at `d:\projects\adonis\extension`.
5. Pin **Adonis** to your Chrome toolbar.

---

### 4. How to Use & Test

1. **Configure Your Profile**:
   - Open [http://localhost:8000](http://localhost:8000).
   - Ingest an existing resume (PDF, DOCX, or Image) or edit the pre-populated candidate vault.
2. **Open a Job Application**:
   - Navigate to any job listing or test locally by opening [http://localhost:8000/demo](http://localhost:8000/demo).
3. **Trigger Adonis**:
   - Click the **Adonis icon** in your toolbar or open the Chrome Side Panel.
   - Click **🔍 Analyze & Curate for this Job**.
   - Review your **ATS Match Score**, highlighted skills, and generated screening answers.
   - (Optional) Download tailored **PDF** or **DOCX** resumes.
4. **Autofill**:
   - Click **⚡ Autofill Application Form**.
   - Watch the fields populate automatically and turn green!
   - Review the populated data and submit when ready.

---

## 🧪 Running Automated Tests

```bash
backend\.venv\Scripts\pytest
```

---

## 🛡️ Privacy & Safety Guarantee

- Your data is stored locally in your environment (`data/`).
- Adonis **never** submits forms automatically; it only fills inputs and displays them for your final verification.
