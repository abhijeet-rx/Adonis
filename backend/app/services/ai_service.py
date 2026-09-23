import json
import re
from typing import Dict, List, Optional, Tuple, Any
import httpx
from app.core.config import GEMINI_API_KEY, OPENAI_API_KEY
from app.models.profile import (
    CandidateProfile,
    JobAnalysisResult,
    CuratedResume,
    WorkExperience,
    Project,
    SkillBank,
    Education
)

class AIService:
    @staticmethod
    def _clean_json_markdown(text: str) -> str:
        """Strip markdown fences (```json ... ```) from LLM output."""
        cleaned = text.strip()
        if cleaned.startswith("```"):
            parts = cleaned.split("\n", 1)
            if len(parts) > 1:
                cleaned = parts[1]
        if cleaned.endswith("```"):
            cleaned = cleaned.rsplit("```", 1)[0]
        return cleaned.strip()

    @classmethod
    async def _call_gemini(cls, prompt: str, image_tuple: Optional[Tuple[str, str]] = None) -> Optional[str]:
        if not GEMINI_API_KEY:
            return None
        
        url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key={GEMINI_API_KEY}"
        
        parts: List[Dict[str, Any]] = [{"text": prompt}]
        if image_tuple:
            mime_type, b64_data = image_tuple
            parts.insert(0, {
                "inline_data": {
                    "mime_type": mime_type,
                    "data": b64_data
                }
            })
            
        payload = {
            "contents": [{"parts": parts}],
            "generationConfig": {"temperature": 0.2, "response_mime_type": "application/json"}
        }

        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                res = await client.post(url, json=payload)
                if res.status_code == 200:
                    data = res.json()
                    candidates = data.get("candidates", [])
                    if candidates:
                        content_parts = candidates[0].get("content", {}).get("parts", [])
                        if content_parts:
                            return content_parts[0].get("text", "")
        except Exception as e:
            print(f"[AIService] Gemini API error: {e}")
        return None

    @classmethod
    async def parse_resume_to_profile(
        cls,
        raw_text: str,
        image_tuple: Optional[Tuple[str, str]] = None
    ) -> CandidateProfile:
        """
        Parses resume text or image into a structured CandidateProfile.
        Uses LLM if key is configured, else falls back to smart regex/heuristic extractor.
        """
        prompt = f"""You are an expert resume parsing engine.
Extract all structured details from the following resume into a valid JSON object matching this schema:
{{
  "first_name": "...",
  "last_name": "...",
  "email": "...",
  "phone": "...",
  "location": "...",
  "headline": "...",
  "summary": "...",
  "linkedin_url": "...",
  "github_url": "...",
  "portfolio_url": "...",
  "skills": {{
    "languages": ["..."],
    "frameworks": ["..."],
    "cloud_devops": ["..."],
    "databases": ["..."],
    "tools": ["..."],
    "soft_skills": ["..."]
  }},
  "experiences": [
    {{
      "company": "...",
      "role": "...",
      "location": "...",
      "start_date": "...",
      "end_date": "...",
      "current": false,
      "bullet_points": ["..."],
      "technologies": ["..."]
    }}
  ],
  "education": [
    {{
      "institution": "...",
      "degree": "...",
      "field_of_study": "...",
      "start_year": "...",
      "end_year": "...",
      "gpa": "..."
    }}
  ]
}}

Resume text:
{raw_text}
"""
        response_text = await cls._call_gemini(prompt, image_tuple)
        if response_text:
            try:
                data = json.loads(cls._clean_json_markdown(response_text))
                return CandidateProfile(**data)
            except Exception as e:
                print(f"[AIService] JSON parsing failed from LLM: {e}")

        # Local smart heuristic fallback
        return cls._fallback_parse_resume(raw_text)

    @staticmethod
    def _fallback_parse_resume(text: str) -> CandidateProfile:
        email_match = re.search(r'[\w\.-]+@[\w\.-]+\.\w+', text)
        phone_match = re.search(r'(\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}', text)
        linkedin_match = re.search(r'https?://(www\.)?linkedin\.com/in/[\w-]+', text)
        github_match = re.search(r'https?://(www\.)?github\.com/[\w-]+', text)
        
        lines = [line.strip() for line in text.split("\n") if line.strip()]
        first_name, last_name = "Candidate", "User"
        headline = "Software Professional"
        
        if lines:
            words = lines[0].split()
            if len(words) >= 2:
                first_name, last_name = words[0], words[1]
            elif len(words) == 1:
                first_name = words[0]
            if len(lines) > 1 and len(lines[1]) < 80:
                headline = lines[1]

        # Extract common tech keywords
        common_tech = ["Python", "JavaScript", "TypeScript", "React", "Node.js", "FastAPI", "SQL", "Docker", "AWS", "Git", "PostgreSQL", "Next.js"]
        found_skills = [s for s in common_tech if re.search(rf'\b{re.escape(s)}\b', text, re.IGNORECASE)]
        
        return CandidateProfile(
            first_name=first_name,
            last_name=last_name,
            email=email_match.group(0) if email_match else "applicant@example.com",
            phone=phone_match.group(0) if phone_match else "",
            headline=headline,
            summary=lines[2] if len(lines) > 2 else "Experienced engineer ready for new challenges.",
            linkedin_url=linkedin_match.group(0) if linkedin_match else "",
            github_url=github_match.group(0) if github_match else "",
            skills=SkillBank(languages=found_skills or ["Python", "JavaScript", "SQL"])
        )

    @classmethod
    async def analyze_job_description(cls, raw_text: str, job_title: str = "", company_name: str = "") -> JobAnalysisResult:
        """Analyzes a job description to extract requirements, tech stack, and keywords."""
        prompt = f"""You are an ATS parser and job market analyst.
Analyze the following job description and extract key details in JSON format:
{{
  "job_title": "{job_title or 'Extract from text'}",
  "company_name": "{company_name or 'Extract from text'}",
  "summary": "Brief 2-sentence summary of the role",
  "required_skills": ["List of must-have technical and domain skills"],
  "preferred_skills": ["List of nice-to-have or preferred qualifications"],
  "key_responsibilities": ["Top 4-5 core duties"],
  "ats_keywords": ["Keywords and phrases the ATS scanner will check for"],
  "tone_and_culture": "e.g. Fast-paced startup, Enterprise collaborative, High autonomy"
}}

Job Description text:
{raw_text[:4000]}
"""
        response_text = await cls._call_gemini(prompt)
        if response_text:
            try:
                data = json.loads(cls._clean_json_markdown(response_text))
                return JobAnalysisResult(**data)
            except Exception as e:
                print(f"[AIService] Job analysis JSON parsing failed: {e}")

        # Heuristic fallback
        title = job_title or "Software Engineer"
        company = company_name or "Target Company"
        
        tech_dictionary = [
            "React", "TypeScript", "JavaScript", "Python", "FastAPI", "Node.js", "Next.js", 
            "AWS", "Docker", "Kubernetes", "PostgreSQL", "MongoDB", "Redis", "GraphQL", 
            "REST", "CI/CD", "Git", "Microservices", "System Design", "Agile", "Tailwind"
        ]
        detected_keywords = [t for t in tech_dictionary if re.search(rf'\b{re.escape(t)}\b', raw_text, re.IGNORECASE)]
        if not detected_keywords:
            detected_keywords = ["Python", "React", "TypeScript", "REST APIs", "Git"]

        return JobAnalysisResult(
            job_title=title,
            company_name=company,
            summary=f"{title} position at {company} focusing on scalable software delivery.",
            required_skills=detected_keywords[:6],
            preferred_skills=detected_keywords[6:10],
            key_responsibilities=[
                f"Design and develop responsive, resilient services for {company}.",
                "Collaborate with product and design teams to deliver high quality features.",
                "Maintain code quality, test coverage, and modern deployment standards."
            ],
            ats_keywords=detected_keywords,
            tone_and_culture="Collaborative, modern, engineering excellence"
        )

    @classmethod
    async def curate_resume_and_skills(
        cls,
        profile: CandidateProfile,
        job_analysis: JobAnalysisResult
    ) -> CuratedResume:
        """
        Dynamically tailors resume, selects optimal skills, and crafts ATS-aligned summary.
        """
        # Pool all candidate skills
        candidate_skills_flat = set(
            profile.skills.languages +
            profile.skills.frameworks +
            profile.skills.cloud_devops +
            profile.skills.databases +
            profile.skills.tools +
            profile.skills.soft_skills
        )

        job_keywords = set(job_analysis.ats_keywords + job_analysis.required_skills)
        
        # Calculate overlap
        matching = [s for s in candidate_skills_flat if any(k.lower() in s.lower() or s.lower() in k.lower() for k in job_keywords)]
        missing = [k for k in job_analysis.required_skills if not any(k.lower() in s.lower() for s in candidate_skills_flat)]
        
        match_score = int((len(matching) / max(len(job_analysis.required_skills), 1)) * 100)
        match_score = min(max(match_score, 50), 98)

        prompt = f"""You are an elite career strategist and ATS resume optimizer.
A candidate with this background:
- Name: {profile.first_name} {profile.last_name}
- Headline: {profile.headline}
- Current Summary: {profile.summary}
- Skills: {list(candidate_skills_flat)}
- Experiences: {[e.model_dump() for e in profile.experiences]}

Is applying for:
- Role: {job_analysis.job_title}
- Company: {job_analysis.company_name}
- Required Skills: {job_analysis.required_skills}
- ATS Keywords: {job_analysis.ats_keywords}

Generate a tailored resume package in valid JSON:
{{
  "tailored_headline": "Compelling headline tailored specifically to {job_analysis.job_title}",
  "tailored_summary": "3-sentence ATS-tailored executive summary highlighting relevant accomplishments",
  "highlighted_skills": ["Top 10-14 skills ordered strictly by relevance to this job"],
  "suggested_cover_letter": "Short, persuasive 3-paragraph cover letter tailored to {job_analysis.company_name}"
}}
"""
        response_text = await cls._call_gemini(prompt)
        if response_text:
            try:
                data = json.loads(cls._clean_json_markdown(response_text))
                return CuratedResume(
                    tailored_headline=data.get("tailored_headline", f"{job_analysis.job_title} | {profile.headline}"),
                    tailored_summary=data.get("tailored_summary", profile.summary),
                    highlighted_skills=data.get("highlighted_skills", list(candidate_skills_flat)[:12]),
                    tailored_experiences=profile.experiences,
                    tailored_projects=profile.projects,
                    ats_match_score=match_score,
                    matching_keywords=matching,
                    missing_keywords=missing[:5],
                    suggested_cover_letter=data.get("suggested_cover_letter", "")
                )
            except Exception as e:
                print(f"[AIService] Curate resume JSON parse error: {e}")

        # Fallback curation
        highlighted = sorted(list(candidate_skills_flat), key=lambda s: any(k.lower() in s.lower() for k in job_keywords), reverse=True)
        tailored_summary = (
            f"Accomplished Software Engineer with proven expertise in {', '.join(job_analysis.required_skills[:3]) if job_analysis.required_skills else 'modern engineering'}. "
            f"Demonstrated history of building high-performance systems and eager to contribute to {job_analysis.company_name} as a {job_analysis.job_title}."
        )
        cover_letter = (
            f"Dear Hiring Team at {job_analysis.company_name},\n\n"
            f"I am writing to express my strong enthusiasm for the {job_analysis.job_title} role. "
            f"With my extensive background in {', '.join(job_analysis.required_skills[:3])}, I have delivered scalable systems that directly improved user experience and operational efficiency.\n\n"
            f"I look forward to discussing how my experience aligns with {job_analysis.company_name}'s goals.\n\n"
            f"Sincerely,\n{profile.first_name} {profile.last_name}"
        )

        return CuratedResume(
            tailored_headline=f"{job_analysis.job_title} | {profile.headline}",
            tailored_summary=tailored_summary,
            highlighted_skills=highlighted[:12],
            tailored_experiences=profile.experiences,
            tailored_projects=profile.projects,
            ats_match_score=match_score,
            matching_keywords=matching,
            missing_keywords=missing[:5],
            suggested_cover_letter=cover_letter
        )

    @classmethod
    async def answer_screening_questions(
        cls,
        profile: CandidateProfile,
        job_title: str,
        company_name: str,
        questions: List[str]
    ) -> Dict[str, str]:
        """
        Answers job application screening / essay questions using candidate profile & preferences.
        """
        answers = {}
        unanswered = []

        # Check existing custom_qa bank first
        for q in questions:
            matched = False
            for stored_q, stored_a in profile.custom_qa.items():
                if q.lower() in stored_q.lower() or stored_q.lower() in q.lower():
                    answers[q] = stored_a
                    matched = True
                    break
            if not matched:
                unanswered.append(q)

        if not unanswered:
            return answers

        prompt = f"""You are helping {profile.first_name} {profile.last_name} fill a job application for {job_title} at {company_name}.
Candidate Background:
- Experiences: {[f'{e.role} at {e.company}: {e.bullet_points}' for e in profile.experiences]}
- Work Authorization: {profile.work_authorization}
- Sponsorship needed: {profile.requires_sponsorship}
- Notice Period: {profile.notice_period}
- Salary Expectations: {profile.salary_expectation}

Answer these screening questions truthfully and persuasively from the candidate's first-person perspective ('I').
Return JSON:
{{
  "answers": {{
    "question string exactly": "concise, professional answer"
  }}
}}

Questions:
{unanswered}
"""
        response_text = await cls._call_gemini(prompt)
        if response_text:
            try:
                data = json.loads(cls._clean_json_markdown(response_text))
                for q, a in data.get("answers", {}).items():
                    answers[q] = a
                return answers
            except Exception as e:
                print(f"[AIService] Screening QA parse error: {e}")

        # Fallback answers based on question intent
        for q in unanswered:
            q_lower = q.lower()
            if "salary" in q_lower or "compensation" in q_lower:
                answers[q] = profile.salary_expectation
            elif "sponsor" in q_lower or "visa" in q_lower:
                answers[q] = "No, I do not require sponsorship." if not profile.requires_sponsorship else "Yes, I require visa sponsorship."
            elif "authorized" in q_lower or "legally" in q_lower:
                answers[q] = profile.work_authorization
            elif "notice" in q_lower or "start date" in q_lower or "soonest" in q_lower:
                answers[q] = profile.notice_period
            elif "why" in q_lower and ("work" in q_lower or "company" in q_lower or "join" in q_lower):
                answers[q] = f"I am deeply impressed by {company_name}'s innovations in this space. My background closely matches the requirements for {job_title}, and I'm excited by the opportunity to contribute immediately."
            elif "years of" in q_lower or "experience" in q_lower:
                answers[q] = "4+ years of professional hands-on production experience."
            else:
                answers[q] = f"Yes, my hands-on background and engineering practices align directly with what {company_name} is seeking for the {job_title} role."

        return answers
