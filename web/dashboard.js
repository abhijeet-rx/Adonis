// Adonis Web Dashboard Frontend Logic

const API_BASE = "http://localhost:8000/api";
let currentProfile = null;
let currentCurated = null;

document.addEventListener("DOMContentLoaded", () => {
  setupNavigation();
  setupUploadDropzone();
  loadProfile();
  loadApplications();
});

// Tab Switching
function switchTab(tabId) {
  ['profile', 'studio', 'tracker'].forEach(t => {
    const panel = document.getElementById(`panel-${t}`);
    const btn = document.getElementById(`tab-${t}`);
    if (t === tabId) {
      panel.classList.remove('hidden');
      btn.className = "tab-btn px-4 py-1.5 rounded-lg font-medium transition bg-brand-600 text-white shadow";
    } else {
      panel.classList.add('hidden');
      btn.className = "tab-btn px-4 py-1.5 rounded-lg font-medium transition text-slate-400 hover:text-white";
    }
  });
}

function setupNavigation() {
  window.switchTab = switchTab;
  window.saveProfile = saveProfile;
  window.runJobCuration = runJobCuration;
  window.downloadTailoredResume = downloadTailoredResume;
  window.loadApplications = loadApplications;
}

// Toast notification
function showToast(msg) {
  const toast = document.getElementById("toast");
  const toastMsg = document.getElementById("toast-msg");
  toastMsg.textContent = msg;
  toast.classList.remove("translate-y-20", "opacity-0");
  setTimeout(() => {
    toast.classList.add("translate-y-20", "opacity-0");
  }, 4000);
}

// 1. Profile Loading and Saving
async function loadProfile() {
  try {
    const res = await fetch(`${API_BASE}/profile`);
    if (res.ok) {
      currentProfile = await res.json();
      populateProfileForm(currentProfile);
    }
  } catch (e) {
    console.error("Failed to load profile:", e);
  }
}

function populateProfileForm(p) {
  document.getElementById("p-first-name").value = p.first_name || "";
  document.getElementById("p-last-name").value = p.last_name || "";
  document.getElementById("p-email").value = p.email || "";
  document.getElementById("p-phone").value = p.phone || "";
  document.getElementById("p-location").value = p.location || "";
  document.getElementById("p-headline").value = p.headline || "";
  document.getElementById("p-linkedin").value = p.linkedin_url || "";
  document.getElementById("p-github").value = p.github_url || "";
  document.getElementById("p-portfolio").value = p.portfolio_url || "";
  document.getElementById("p-summary").value = p.summary || "";
  
  document.getElementById("p-work-auth").value = p.work_authorization || "";
  document.getElementById("p-salary").value = p.salary_expectation || "";
  document.getElementById("p-notice").value = p.notice_period || "";

  if (p.skills) {
    document.getElementById("p-skills-lang").value = (p.skills.languages || []).join(", ");
    document.getElementById("p-skills-frame").value = (p.skills.frameworks || []).join(", ");
    document.getElementById("p-skills-cloud").value = (p.skills.cloud_devops || []).join(", ");
    document.getElementById("p-skills-db").value = (p.skills.databases || []).join(", ");
  }
}

async function saveProfile() {
  if (!currentProfile) currentProfile = {};

  currentProfile.first_name = document.getElementById("p-first-name").value;
  currentProfile.last_name = document.getElementById("p-last-name").value;
  currentProfile.email = document.getElementById("p-email").value;
  currentProfile.phone = document.getElementById("p-phone").value;
  currentProfile.location = document.getElementById("p-location").value;
  currentProfile.headline = document.getElementById("p-headline").value;
  currentProfile.linkedin_url = document.getElementById("p-linkedin").value;
  currentProfile.github_url = document.getElementById("p-github").value;
  currentProfile.portfolio_url = document.getElementById("p-portfolio").value;
  currentProfile.summary = document.getElementById("p-summary").value;

  currentProfile.work_authorization = document.getElementById("p-work-auth").value;
  currentProfile.salary_expectation = document.getElementById("p-salary").value;
  currentProfile.notice_period = document.getElementById("p-notice").value;

  const splitSkills = (val) => val.split(",").map(s => s.trim()).filter(Boolean);
  currentProfile.skills = {
    languages: splitSkills(document.getElementById("p-skills-lang").value),
    frameworks: splitSkills(document.getElementById("p-skills-frame").value),
    cloud_devops: splitSkills(document.getElementById("p-skills-cloud").value),
    databases: splitSkills(document.getElementById("p-skills-db").value),
    tools: currentProfile.skills?.tools || ["Git", "Docker"],
    soft_skills: currentProfile.skills?.soft_skills || ["Collaboration"]
  };

  try {
    const res = await fetch(`${API_BASE}/profile`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(currentProfile)
    });
    if (res.ok) {
      showToast("Profile successfully saved to Adonis vault!");
    }
  } catch (e) {
    alert("Failed to save profile: " + e.message);
  }
}

// 2. Resume File Ingestion (PDF, DOCX, Image)
function setupUploadDropzone() {
  const dropzone = document.getElementById("dropzone");
  const fileInput = document.getElementById("resume-file-input");

  dropzone.addEventListener("click", () => fileInput.click());

  dropzone.addEventListener("dragover", (e) => {
    e.preventDefault();
    dropzone.classList.add("border-brand-500", "bg-slate-800/50");
  });

  dropzone.addEventListener("dragleave", () => {
    dropzone.classList.remove("border-brand-500", "bg-slate-800/50");
  });

  dropzone.addEventListener("drop", (e) => {
    e.preventDefault();
    dropzone.classList.remove("border-brand-500", "bg-slate-800/50");
    if (e.dataTransfer.files.length) {
      handleFileUpload(e.dataTransfer.files[0]);
    }
  });

  fileInput.addEventListener("change", () => {
    if (fileInput.files.length) {
      handleFileUpload(fileInput.files[0]);
    }
  });
}

async function handleFileUpload(file) {
  const statusEl = document.getElementById("upload-status");
  statusEl.classList.remove("hidden");
  statusEl.innerHTML = `<span class="text-brand-400">⏳ Uploading and parsing ${file.name}...</span>`;

  const formData = new FormData();
  formData.append("file", file);

  try {
    const res = await fetch(`${API_BASE}/resume/upload`, {
      method: "POST",
      body: formData
    });

    if (res.ok) {
      const parsedProfile = await res.json();
      currentProfile = parsedProfile;
      populateProfileForm(parsedProfile);
      statusEl.innerHTML = `<span class="text-emerald-400">✓ Successfully ingested ${file.name}! Profile vault updated.</span>`;
      showToast("Resume parsed and vault populated!");
    } else {
      const err = await res.json();
      statusEl.innerHTML = `<span class="text-red-400">❌ Error: ${err.detail || "Upload failed"}</span>`;
    }
  } catch (err) {
    statusEl.innerHTML = `<span class="text-red-400">❌ Connection error: ${err.message}</span>`;
  }
}

// 3. AI Studio & Job Curation
async function runJobCuration() {
  const company = document.getElementById("studio-company").value.trim() || "Target Employer";
  const title = document.getElementById("studio-title").value.trim() || "Software Engineer";
  const rawText = document.getElementById("studio-job-text").value.trim();

  if (!rawText) {
    alert("Please paste the job description text to run AI curation.");
    return;
  }

  const btn = document.getElementById("btn-studio-curate");
  btn.disabled = true;
  btn.innerHTML = `<span>⏳ Analyzing Job & Tailoring Profile...</span>`;

  try {
    // 1. Analyze Job
    const analysisRes = await fetch(`${API_BASE}/job/analyze`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        company_name: company,
        job_title: title,
        raw_text: rawText
      })
    });
    const jobAnalysis = await analysisRes.json();

    // 2. Curate Resume
    const curateRes = await fetch(`${API_BASE}/resume/curate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        job_analysis: jobAnalysis,
        candidate_profile: currentProfile
      })
    });
    currentCurated = await curateRes.json();

    // 3. Render Output
    renderStudioResults(currentCurated);

  } catch (e) {
    alert("Curation failed: " + e.message);
  } finally {
    btn.disabled = false;
    btn.innerHTML = `<span>✨ Run AI Curation & ATS Optimization</span>`;
  }
}

function renderStudioResults(curated) {
  document.getElementById("studio-empty-state").classList.add("hidden");
  const resultsEl = document.getElementById("studio-results");
  resultsEl.classList.remove("hidden");

  document.getElementById("res-score").textContent = `${curated.ats_match_score}%`;
  document.getElementById("res-headline").textContent = curated.tailored_headline;
  document.getElementById("res-summary").textContent = curated.tailored_summary;
  document.getElementById("res-cover-letter").value = curated.suggested_cover_letter || "";

  // Matching tags
  const matchContainer = document.getElementById("res-matching-skills");
  matchContainer.innerHTML = "";
  (curated.matching_keywords || []).forEach(k => {
    const span = document.createElement("span");
    span.className = "text-xs px-2.5 py-1 rounded-md bg-emerald-500/10 text-emerald-300 border border-emerald-500/20";
    span.textContent = `✓ ${k}`;
    matchContainer.appendChild(span);
  });

  // Missing tags
  const missingContainer = document.getElementById("res-missing-skills");
  missingContainer.innerHTML = "";
  (curated.missing_keywords || []).forEach(k => {
    const span = document.createElement("span");
    span.className = "text-xs px-2.5 py-1 rounded-md bg-amber-500/10 text-amber-300 border border-amber-500/20";
    span.textContent = `+ ${k}`;
    missingContainer.appendChild(span);
  });
}

async function downloadTailoredResume(format) {
  if (!currentCurated) return;

  const endpoint = format === "pdf" ? "/resume/export/pdf" : "/resume/export/docx";
  const filename = format === "pdf" ? "Adonis_Tailored_Resume.pdf" : "Adonis_Tailored_Resume.docx";

  try {
    const res = await fetch(`${API_BASE}${endpoint}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(currentCurated)
    });

    const blob = await res.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    showToast(`Downloaded tailored ATS ${format.toUpperCase()}!`);
  } catch (e) {
    alert("Download failed: " + e.message);
  }
}

// 4. Application Tracker
async function loadApplications() {
  try {
    const res = await fetch(`${API_BASE}/applications`);
    if (res.ok) {
      const apps = await res.json();
      const tbody = document.getElementById("applications-table-body");
      if (!apps || apps.length === 0) {
        tbody.innerHTML = `<tr><td colspan="6" class="px-4 py-8 text-center text-slate-500 text-xs">No application records found. Autofill applications using the Chrome Extension to see them logged here.</td></tr>`;
        return;
      }
      tbody.innerHTML = "";
      apps.forEach(app => {
        const tr = document.createElement("tr");
        tr.className = "hover:bg-slate-900/50 transition";
        const dateStr = app.timestamp ? new Date(app.timestamp).toLocaleDateString() : "Recent";
        tr.innerHTML = `
          <td class="px-4 py-3 font-semibold text-white">${app.company || "Unknown"}</td>
          <td class="px-4 py-3 text-slate-300">${app.role || "Role"}</td>
          <td class="px-4 py-3 text-slate-400 text-xs">${dateStr}</td>
          <td class="px-4 py-3"><span class="px-2 py-0.5 rounded text-xs font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">${app.ats_match_score || 85}%</span></td>
          <td class="px-4 py-3"><span class="px-2 py-0.5 rounded text-xs font-semibold bg-blue-500/10 text-blue-400 border border-blue-500/20">${app.status || "Autofilled"}</span></td>
          <td class="px-4 py-3 text-xs text-slate-400">${app.url ? `<a href="${app.url}" target="_blank" class="text-brand-400 hover:underline">View Link ↗</a>` : '—'}</td>
        `;
        tbody.appendChild(tr);
      });
    }
  } catch (e) {
    console.error("Failed to load applications:", e);
  }
}
