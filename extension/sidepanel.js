// Adonis Sidepanel / Popup Logic

const BACKEND_URL = "http://localhost:8000/api";

let candidateProfile = null;
let currentJobAnalysis = null;
let currentCuratedResume = null;
let currentScreeningAnswers = {};

document.addEventListener("DOMContentLoaded", async () => {
  setupUI();
  await checkBackendStatus();
  await loadCandidateProfile();
  await inspectActiveTab();
});

function setupUI() {
  const btnAnalyze = document.getElementById("btn-analyze");
  const btnAutofill = document.getElementById("btn-autofill");
  const btnDownloadPdf = document.getElementById("btn-download-pdf");
  const btnDownloadDocx = document.getElementById("btn-download-docx");
  const btnOpenSidepanel = document.getElementById("btn-open-sidepanel");

  if (btnAnalyze) btnAnalyze.addEventListener("click", handleAnalyzeJob);
  if (btnAutofill) btnAutofill.addEventListener("click", handleAutofill);
  if (btnDownloadPdf) btnDownloadPdf.addEventListener("click", () => handleDownloadResume("pdf"));
  if (btnDownloadDocx) btnDownloadDocx.addEventListener("click", () => handleDownloadResume("docx"));

  if (btnOpenSidepanel) {
    btnOpenSidepanel.addEventListener("click", async () => {
      if (chrome.sidePanel && chrome.sidePanel.open) {
        const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
        if (tab?.windowId) {
          chrome.sidePanel.open({ windowId: tab.windowId });
        }
      }
    });
  }
}

async function checkBackendStatus() {
  const statusBadge = document.getElementById("connection-status");
  try {
    const res = await fetch("http://localhost:8000/");
    if (res.ok) {
      statusBadge.textContent = "Engine Connected";
      statusBadge.className = "status-badge status-online";
    } else {
      throw new Error();
    }
  } catch (err) {
    statusBadge.textContent = "Offline (Start Backend)";
    statusBadge.className = "status-badge status-offline";
  }
}

async function loadCandidateProfile() {
  try {
    const res = await fetch(`${BACKEND_URL}/profile`);
    if (res.ok) {
      candidateProfile = await res.json();
      document.getElementById("candidate-name").textContent = `${candidateProfile.first_name} ${candidateProfile.last_name}`;
      document.getElementById("candidate-headline").textContent = candidateProfile.headline || "Candidate Profile Loaded";
      const initials = `${candidateProfile.first_name[0] || ""}${candidateProfile.last_name[0] || ""}`.toUpperCase();
      document.querySelector(".avatar").textContent = initials || "AD";
    }
  } catch (e) {
    console.error("Failed to load profile:", e);
  }
}

async function inspectActiveTab() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab) return;

    document.getElementById("page-title").textContent = tab.title || tab.url;

    // Send extract message to content script
    chrome.tabs.sendMessage(tab.id, { action: "EXTRACT_JOB_DETAILS" }, (response) => {
      if (chrome.runtime.lastError || !response) {
        document.getElementById("detected-platform").textContent = "Generic Form";
        return;
      }
      const data = response.data;
      const platformTag = document.getElementById("detected-platform");
      if (data.platform === "greenhouse") platformTag.textContent = "Greenhouse ATS";
      else if (data.platform === "lever") platformTag.textContent = "Lever ATS";
      else if (data.platform === "ashby") platformTag.textContent = "Ashby ATS";
      else platformTag.textContent = "Standard / Web Form";
    });
  } catch (e) {
    console.error("Failed to inspect tab:", e);
  }
}

async function handleAnalyzeJob() {
  const btnAnalyze = document.getElementById("btn-analyze");
  btnAnalyze.disabled = true;
  btnAnalyze.innerHTML = `<span class="btn-icon">⏳</span> Analyzing Job Description...`;

  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    
    // 1. Get raw text from active page
    let rawText = "";
    let pageTitle = tab.title || "Job Application";

    try {
      const response = await new Promise((resolve) => {
        chrome.tabs.sendMessage(tab.id, { action: "EXTRACT_JOB_DETAILS" }, (res) => {
          if (chrome.runtime.lastError) resolve(null);
          else resolve(res);
        });
      });
      if (response?.data?.rawText) {
        rawText = response.data.rawText;
        pageTitle = response.data.pageTitle || pageTitle;
      }
    } catch (e) {
      console.warn("Could not message content script:", e);
    }

    if (!rawText || rawText.length < 50) {
      rawText = `${pageTitle}. Requirements: Full Stack, React, TypeScript, Python, Cloud, REST APIs.`;
    }

    // 2. Call Backend Job Analyzer
    const analyzeRes = await fetch(`${BACKEND_URL}/job/analyze`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        job_title: pageTitle.substring(0, 100),
        raw_text: rawText
      })
    });
    currentJobAnalysis = await analyzeRes.json();

    // 3. Call Backend Resume Curator
    const curateRes = await fetch(`${BACKEND_URL}/resume/curate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        job_analysis: currentJobAnalysis,
        candidate_profile: candidateProfile
      })
    });
    currentCuratedResume = await curateRes.json();

    // 4. Generate common screening answers
    const qaRes = await fetch(`${BACKEND_URL}/screening/answers`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        job_title: currentJobAnalysis.job_title,
        company_name: currentJobAnalysis.company_name,
        questions: [
          "Why do you want to work here?",
          "Are you authorized to work in the country?",
          "What are your salary expectations?"
        ]
      })
    });
    const qaData = await qaRes.json();
    currentScreeningAnswers = qaData.answers || {};

    // 5. Update UI
    renderAnalysisResults();

  } catch (err) {
    console.error("Job analysis error:", err);
    alert("Could not connect to Adonis backend. Please verify http://localhost:8000 is running.");
  } finally {
    btnAnalyze.disabled = false;
    btnAnalyze.innerHTML = `<span class="btn-icon">🔍</span> Re-Analyze Job`;
  }
}

function renderAnalysisResults() {
  document.getElementById("analysis-panel").classList.remove("hidden");
  document.getElementById("qa-panel").classList.remove("hidden");

  // ATS Score
  const score = currentCuratedResume.ats_match_score || 85;
  document.getElementById("match-score").textContent = `${score}%`;
  document.getElementById("score-bar").style.width = `${score}%`;

  // Headline
  document.getElementById("tailored-headline").textContent = currentCuratedResume.tailored_headline;

  // Matching tags
  const matchingContainer = document.getElementById("matching-tags");
  matchingContainer.innerHTML = "";
  (currentCuratedResume.matching_keywords || []).forEach(kw => {
    const span = document.createElement("span");
    span.className = "tag-pill tag-match";
    span.textContent = `✓ ${kw}`;
    matchingContainer.appendChild(span);
  });

  // Missing tags
  const missingContainer = document.getElementById("missing-tags");
  missingContainer.innerHTML = "";
  (currentCuratedResume.missing_keywords || []).forEach(kw => {
    const span = document.createElement("span");
    span.className = "tag-pill tag-missing";
    span.textContent = `+ ${kw}`;
    missingContainer.appendChild(span);
  });

  // Q&A List
  const qaList = document.getElementById("qa-list");
  qaList.innerHTML = "";
  for (const [q, a] of Object.entries(currentScreeningAnswers)) {
    const item = document.createElement("div");
    item.className = "qa-item";
    item.innerHTML = `
      <div class="qa-q">${q}</div>
      <div class="qa-a">${a}</div>
    `;
    qaList.appendChild(item);
  }

  // Enable Autofill Button
  const btnAutofill = document.getElementById("btn-autofill");
  btnAutofill.disabled = false;
}

async function handleAutofill() {
  const btnAutofill = document.getElementById("btn-autofill");
  btnAutofill.disabled = true;
  btnAutofill.innerHTML = `<span class="btn-icon">⏳</span> Autofilling Form...`;

  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    
    // Inject and send autofill payload to active tab
    chrome.tabs.sendMessage(tab.id, {
      action: "AUTOFILL_FORM",
      payload: {
        profile: candidateProfile,
        curatedResume: currentCuratedResume,
        answers: currentScreeningAnswers
      }
    }, async (res) => {
      const filledCount = res?.filledCount || 0;
      btnAutofill.innerHTML = `<span class="btn-icon">✓</span> Autofilled ${filledCount} Fields!`;
      btnAutofill.disabled = false;

      // Log application event to backend
      try {
        await fetch(`${BACKEND_URL}/applications/log`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            company: currentJobAnalysis?.company_name || "Unknown Company",
            role: currentJobAnalysis?.job_title || tab.title || "Job Application",
            url: tab.url,
            status: "Autofilled - Ready for Review",
            ats_match_score: currentCuratedResume?.ats_match_score || 0
          })
        });
      } catch (e) {
        console.warn("Could not log application:", e);
      }
    });

  } catch (err) {
    console.error("Autofill failed:", err);
    btnAutofill.disabled = false;
    btnAutofill.innerHTML = `<span class="btn-icon">⚡</span> Autofill Application Form`;
  }
}

async function handleDownloadResume(format) {
  if (!currentCuratedResume) return;

  const endpoint = format === "pdf" ? "/resume/export/pdf" : "/resume/export/docx";
  const filename = format === "pdf" ? "Adonis_Tailored_Resume.pdf" : "Adonis_Tailored_Resume.docx";

  try {
    const res = await fetch(`${BACKEND_URL}${endpoint}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(currentCuratedResume)
    });

    const blob = await res.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } catch (e) {
    console.error("Resume download failed:", e);
  }
}
