import json
from pathlib import Path
from typing import Optional, List, Dict, Any
from app.core.config import DATA_DIR
from app.models.profile import CandidateProfile, WorkExperience, Education, Project, SkillBank

PROFILE_FILE = DATA_DIR / "candidate_profile.json"
APPLICATIONS_FILE = DATA_DIR / "applications.json"

DEFAULT_MOCK_PROFILE = CandidateProfile(
    first_name="Alex",
    last_name="Mercer",
    email="alex.mercer.dev@gmail.com",
    phone="+1 (555) 234-5678",
    location="San Francisco, CA (Open to Remote)",
    headline="Full Stack Software Engineer | React, Node.js, Python, Cloud",
    summary="Results-driven Software Engineer with 4+ years of experience designing, building, and deploying scalable web applications, microservices, and AI integrations. Strong background in React, TypeScript, Python, and cloud infrastructure.",
    linkedin_url="https://linkedin.com/in/alex-mercer-dev",
    github_url="https://github.com/alex-mercer",
    portfolio_url="https://alexmercer.dev",
    twitter_url="https://x.com/alexmercer_dev",
    work_authorization="Authorized to work in the United States without restriction",
    requires_sponsorship=False,
    notice_period="2 weeks",
    salary_expectation="$130,000 - $155,000 USD",
    willing_to_relocate=True,
    remote_preference="Flexible (Remote / Hybrid)",
    skills=SkillBank(
        languages=["TypeScript", "JavaScript", "Python", "SQL", "HTML5", "CSS3/Tailwind"],
        frameworks=["React", "Next.js", "FastAPI", "Node.js", "Express", "Vue.js"],
        cloud_devops=["AWS (S3, Lambda, EC2)", "Docker", "GitHub Actions", "PostgreSQL", "Redis"],
        databases=["PostgreSQL", "MongoDB", "Redis", "SQLite"],
        tools=["Git", "Postman", "Jest", "Vite", "Webpack", "Figma"],
        soft_skills=["System Design", "Agile/Scrum", "Technical Leadership", "Cross-functional Collaboration"]
    ),
    experiences=[
        WorkExperience(
            id="exp-1",
            company="Nexora Tech",
            role="Senior Full Stack Engineer",
            location="San Francisco, CA",
            start_date="2022-03",
            end_date="Present",
            current=True,
            bullet_points=[
                "Architected high-throughput REST APIs and microservices in Python FastAPI and Node.js serving 250k+ daily active users.",
                "Engineered responsive React and Next.js frontends with TypeScript, improving Core Web Vitals by 42%.",
                "Spearheaded adoption of automated CI/CD pipelines via GitHub Actions and Docker, reducing release cycle time by 60%."
            ],
            technologies=["React", "TypeScript", "FastAPI", "Docker", "PostgreSQL", "AWS"]
        ),
        WorkExperience(
            id="exp-2",
            company="CloudScale Solutions",
            role="Software Engineer",
            location="Austin, TX",
            start_date="2020-06",
            end_date="2022-02",
            current=False,
            bullet_points=[
                "Developed scalable client dashboards using React, Redux, and Tailwind CSS for cloud infrastructure monitoring.",
                "Optimized SQL queries and database indexes in PostgreSQL, cutting median query latency by 35%.",
                "Built unit and integration test suites achieving 88% test coverage with Jest and PyTest."
            ],
            technologies=["React", "Node.js", "PostgreSQL", "Redis", "Jest"]
        )
    ],
    education=[
        Education(
            id="edu-1",
            institution="University of California, Berkeley",
            degree="Bachelor of Science in Computer Science",
            field_of_study="Computer Science",
            start_year="2016",
            end_year="2020",
            gpa="3.8/4.0"
        )
    ],
    projects=[
        Project(
            id="proj-1",
            title="AutoApply AI Copilot",
            description="Autonomous browser extension & backend that analyzes job specs and automates form filling.",
            technologies=["React", "TypeScript", "FastAPI", "Chrome Extensions MV3", "Gemini API"],
            link="https://github.com/alex-mercer/autoapply"
        )
    ],
    custom_qa={
        "Why do you want to work here?": "I am passionate about building impactful developer tools and products that leverage cutting-edge AI and seamless UX. Your team's mission and engineering standards closely align with my career goals.",
        "What is your greatest technical achievement?": "Redesigning a legacy data pipeline to process 10M+ events daily with zero downtime, cutting server costs by 45% and latency by half.",
        "Are you willing to work in a hybrid/remote environment?": "Yes, I am comfortable with both remote and hybrid setups and thrive in asynchronous, communicative environments."
    }
)

class ProfileStore:
    @classmethod
    def get_profile(cls) -> CandidateProfile:
        if not PROFILE_FILE.exists():
            cls.save_profile(DEFAULT_MOCK_PROFILE)
            return DEFAULT_MOCK_PROFILE
        try:
            with open(PROFILE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                return CandidateProfile(**data)
        except Exception:
            return DEFAULT_MOCK_PROFILE

    @classmethod
    def save_profile(cls, profile: CandidateProfile) -> CandidateProfile:
        with open(PROFILE_FILE, "w", encoding="utf-8") as f:
            json.dump(profile.model_dump(), f, indent=2, ensure_ascii=False)
        return profile

    @classmethod
    def get_applications(cls) -> List[Dict[str, Any]]:
        if not APPLICATIONS_FILE.exists():
            return []
        try:
            with open(APPLICATIONS_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return []

    @classmethod
    def log_application(cls, app_data: Dict[str, Any]) -> List[Dict[str, Any]]:
        apps = cls.get_applications()
        apps.insert(0, app_data)
        with open(APPLICATIONS_FILE, "w", encoding="utf-8") as f:
            json.dump(apps, f, indent=2, ensure_ascii=False)
        return apps
