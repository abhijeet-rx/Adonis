// Adonis Content Script - Form Auto-Filler & Page Extractor

(function () {
  console.log("[Adonis] Content script loaded and active.");

  // Helper to trigger realistic input events for React / Vue / Angular forms
  function setNativeValue(element, value) {
    if (!element || value === undefined || value === null) return false;
    
    // Choose prototype based on element tag
    const prototype = element instanceof HTMLTextAreaElement 
      ? window.HTMLTextAreaElement.prototype 
      : window.HTMLInputElement.prototype;

    const descriptor = Object.getOwnPropertyDescriptor(prototype, "value");
    if (descriptor && descriptor.set) {
      descriptor.set.call(element, value);
    } else {
      element.value = value;
    }

    element.dispatchEvent(new Event("input", { bubbles: true }));
    element.dispatchEvent(new Event("change", { bubbles: true }));
    element.dispatchEvent(new Event("blur", { bubbles: true }));
    element.classList.add("adonis-highlight-filled");
    return true;
  }

  function setSelectValue(selectElement, valueText) {
    if (!selectElement) return false;
    const lowerTarget = valueText.toLowerCase();
    for (let i = 0; i < selectElement.options.length; i++) {
      const opt = selectElement.options[i];
      if (opt.text.toLowerCase().includes(lowerTarget) || opt.value.toLowerCase().includes(lowerTarget)) {
        selectElement.selectedIndex = i;
        selectElement.dispatchEvent(new Event("change", { bubbles: true }));
        selectElement.classList.add("adonis-highlight-filled");
        return true;
      }
    }
    return false;
  }

  // Extract text and metadata from the current page
  function extractJobDetails() {
    const url = window.location.href;
    const pageTitle = document.title;
    
    // Platform detection
    let platform = "generic";
    if (url.includes("greenhouse.io") || document.querySelector("#application_form")) platform = "greenhouse";
    else if (url.includes("lever.co") || document.querySelector(".application-form")) platform = "lever";
    else if (url.includes("ashbyhq.com")) platform = "ashby";
    else if (url.includes("myworkdayjobs.com")) platform = "workday";

    // Extract main text (strip scripts and styles)
    const clone = document.body.cloneNode(true);
    const scripts = clone.querySelectorAll("script, style, noscript, nav, footer, header");
    scripts.forEach(s => s.remove());
    const rawText = clone.innerText.replace(/\s+/g, " ").trim().substring(0, 10000);

    return {
      url,
      pageTitle,
      platform,
      rawText
    };
  }

  // Main autofill engine
  function autofillForm(data) {
    const profile = data.profile || {};
    const curated = data.curatedResume || {};
    const customAnswers = data.answers || {};

    let filledCount = 0;
    const fullName = `${profile.first_name || ""} ${profile.last_name || ""}`.trim();

    // 1. Check for Greenhouse specific selectors
    if (document.querySelector("#application_form")) {
      const ghFirst = document.querySelector("#first_name");
      const ghLast = document.querySelector("#last_name");
      const ghEmail = document.querySelector("#email");
      const ghPhone = document.querySelector("#phone");

      if (ghFirst && setNativeValue(ghFirst, profile.first_name)) filledCount++;
      if (ghLast && setNativeValue(ghLast, profile.last_name)) filledCount++;
      if (ghEmail && setNativeValue(ghEmail, profile.email)) filledCount++;
      if (ghPhone && setNativeValue(ghPhone, profile.phone)) filledCount++;
    }

    // 2. Check for Lever specific selectors
    if (document.querySelector(".application-form") || window.location.href.includes("lever.co")) {
      const levName = document.querySelector("input[name='name']");
      const levEmail = document.querySelector("input[name='email']");
      const levPhone = document.querySelector("input[name='phone']");
      const levOrg = document.querySelector("input[name='org']");
      const levComments = document.querySelector("textarea[name='comments']");

      if (levName && setNativeValue(levName, fullName)) filledCount++;
      if (levEmail && setNativeValue(levEmail, profile.email)) filledCount++;
      if (levPhone && setNativeValue(levPhone, profile.phone)) filledCount++;
      if (levOrg && profile.experiences?.[0] && setNativeValue(levOrg, profile.experiences[0].company)) filledCount++;
      if (levComments && curated.suggested_cover_letter && setNativeValue(levComments, curated.suggested_cover_letter)) filledCount++;
    }

    // 3. Heuristic matching across all input and textarea elements
    const inputs = Array.from(document.querySelectorAll("input:not([type='hidden']):not([type='submit']):not([type='button']), textarea, select"));

    inputs.forEach(input => {
      // Skip if already filled
      if (input.classList.contains("adonis-highlight-filled") || (input.value && input.value.trim().length > 0)) {
        return;
      }

      // Gather contextual clues: name, id, placeholder, label, aria-label
      const id = (input.id || "").toLowerCase();
      const name = (input.name || "").toLowerCase();
      const placeholder = (input.placeholder || "").toLowerCase();
      const ariaLabel = (input.getAttribute("aria-label") || "").toLowerCase();
      const autocomplete = (input.autocomplete || "").toLowerCase();

      // Find nearby label text
      let labelText = "";
      if (input.id) {
        const lbl = document.querySelector(`label[for="${input.id}"]`);
        if (lbl) labelText = lbl.innerText.toLowerCase();
      }
      if (!labelText && input.closest("label")) {
        labelText = input.closest("label").innerText.toLowerCase();
      }
      if (!labelText && input.parentElement) {
        labelText = input.parentElement.innerText.toLowerCase();
      }

      const context = `${id} ${name} ${placeholder} ${ariaLabel} ${autocomplete} ${labelText}`;

      // Tag handling for text inputs and textareas
      if (input instanceof HTMLInputElement || input instanceof HTMLTextAreaElement) {
        // Name fields
        if ((context.includes("first name") || context.includes("given-name") || name === "first_name" || id === "first_name") && !context.includes("last")) {
          if (setNativeValue(input, profile.first_name)) filledCount++;
        } else if (context.includes("last name") || context.includes("family-name") || name === "last_name" || id === "last_name") {
          if (setNativeValue(input, profile.last_name)) filledCount++;
        } else if ((context.includes("full name") || name === "name" || id === "name") && !context.includes("company")) {
          if (setNativeValue(input, fullName)) filledCount++;
        }
        // Email
        else if (context.includes("email") || autocomplete === "email" || input.type === "email") {
          if (setNativeValue(input, profile.email)) filledCount++;
        }
        // Phone
        else if (context.includes("phone") || context.includes("mobile") || context.includes("tel") || input.type === "tel") {
          if (setNativeValue(input, profile.phone)) filledCount++;
        }
        // Location / City / Address
        else if (context.includes("city") || context.includes("location") || context.includes("address")) {
          if (setNativeValue(input, profile.location)) filledCount++;
        }
        // LinkedIn
        else if (context.includes("linkedin")) {
          if (setNativeValue(input, profile.linkedin_url)) filledCount++;
        }
        // GitHub
        else if (context.includes("github")) {
          if (setNativeValue(input, profile.github_url)) filledCount++;
        }
        // Portfolio / Website
        else if (context.includes("portfolio") || context.includes("website") || context.includes("personal site")) {
          if (setNativeValue(input, profile.portfolio_url || profile.github_url)) filledCount++;
        }
        // Cover Letter / Summary
        else if (context.includes("cover letter") || context.includes("note") || context.includes("additional information") || context.includes("comments")) {
          const letter = curated.suggested_cover_letter || profile.summary;
          if (letter && setNativeValue(input, letter)) filledCount++;
        }
        // Salary expectations
        else if (context.includes("salary") || context.includes("compensation") || context.includes("pay expectation")) {
          if (setNativeValue(input, profile.salary_expectation)) filledCount++;
        }
        // Notice Period / Start date
        else if (context.includes("notice period") || context.includes("start date") || context.includes("earliest start")) {
          if (setNativeValue(input, profile.notice_period)) filledCount++;
        }
        // Custom screening answers match
        else {
          for (const [q, answer] of Object.entries(customAnswers)) {
            const qKey = q.toLowerCase().replace(/[^a-z0-9]/g, "");
            const ctxKey = context.replace(/[^a-z0-9]/g, "");
            if (ctxKey.includes(qKey) || (q.length > 15 && context.includes(q.substring(0, 15).toLowerCase()))) {
              if (setNativeValue(input, answer)) filledCount++;
              break;
            }
          }
        }
      } 
      // Dropdown / Select handling
      else if (input instanceof HTMLSelectElement) {
        if (context.includes("gender")) {
          if (setSelectValue(input, profile.demographics?.gender || "Decline")) filledCount++;
        } else if (context.includes("veteran")) {
          if (setSelectValue(input, profile.demographics?.veteran_status || "not a protected")) filledCount++;
        } else if (context.includes("disability")) {
          if (setSelectValue(input, profile.demographics?.disability_status || "do not have")) filledCount++;
        } else if (context.includes("sponsorship") || context.includes("visa")) {
          const pref = profile.requires_sponsorship ? "Yes" : "No";
          if (setSelectValue(input, pref)) filledCount++;
        } else if (context.includes("authorized") || context.includes("authorization")) {
          if (setSelectValue(input, "Yes")) filledCount++;
        }
      }
    });

    showFeedbackToast(filledCount);
    return filledCount;
  }

  function showFeedbackToast(count) {
    const existing = document.getElementById("adonis-toast-notification");
    if (existing) existing.remove();

    const toast = document.createElement("div");
    toast.id = "adonis-toast-notification";
    toast.innerHTML = `
      <h4>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5"><polyline points="20 6 9 17 4 12"></polyline></svg>
        Adonis Autofill Complete
      </h4>
      <p><strong>${count} fields</strong> were successfully populated and highlighted in green.</p>
      <p style="margin-top: 6px; color: #f59e0b; font-size: 11px;">
        🛡️ <em>Adonis never auto-submits. Please review all fields before submitting.</em>
      </p>
    `;

    document.body.appendChild(toast);
    setTimeout(() => {
      toast.style.opacity = "0";
      toast.style.transition = "opacity 0.5s ease-out";
      setTimeout(() => toast.remove(), 500);
    }, 6000);
  }

  // Chrome Message Listener
  chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === "EXTRACT_JOB_DETAILS") {
      const details = extractJobDetails();
      sendResponse({ status: "success", data: details });
    } else if (request.action === "AUTOFILL_FORM") {
      const filled = autofillForm(request.payload);
      sendResponse({ status: "success", filledCount: filled });
    }
    return true;
  });

})();
