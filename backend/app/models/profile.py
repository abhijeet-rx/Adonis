from typing import List, Optional, Dict, Any
from pydantic import BaseModel, Field

class WorkExperience(BaseModel):
    id: Optional[str] = None
    company: str
    role: str
    location: Optional[str] = ""
    start_date: str
    end_date: Optional[str] = "Present"
    current: bool = False
    bullet_points: List[str] = Field(default_factory=list)
    technologies: List[str] = Field(default_factory=list)

class Education(BaseModel):
    id: Optional[str] = None
    institution: str
    degree: str
    field_of_study: Optional[str] = ""
    start_year: Optional[str] = ""
    end_year: Optional[str] = ""
    gpa: Optional[str] = ""

class Project(BaseModel):
    id: Optional[str] = None
    title: str
    description: str
    technologies: List[str] = Field(default_factory=list)
    link: Optional[str] = ""

class SkillBank(BaseModel):
    languages: List[str] = Field(default_factory=list)
    frameworks: List[str] = Field(default_factory=list)
    cloud_devops: List[str] = Field(default_factory=list)
    databases: List[str] = Field(default_factory=list)
    tools: List[str] = Field(default_factory=list)
    soft_skills: List[str] = Field(default_factory=list)

class DemographicsDefaults(BaseModel):
    gender: Optional[str] = "Decline to identify"
    veteran_status: Optional[str] = "I am not a protected veteran"
    disability_status: Optional[str] = "I do not have a disability"
    race_ethnicity: Optional[str] = "Decline to identify"

class CandidateProfile(BaseModel):
    first_name: str = ""
    last_name: str = ""
    email: str = ""
    phone: str = ""
    location: str = ""
    headline: str = ""
    summary: str = ""
    
    linkedin_url: str = ""
    github_url: str = ""
    portfolio_url: str = ""
    twitter_url: str = ""
    
    # Screening defaults
    work_authorization: str = "Authorized to work in country of application"
    requires_sponsorship: bool = False
    notice_period: str = "Immediate / 2 weeks"
    salary_expectation: str = "Open / Negotiable"
    willing_to_relocate: bool = True
    remote_preference: str = "Flexible (Remote, Hybrid, or On-site)"
    
    demographics: DemographicsDefaults = Field(default_factory=DemographicsDefaults)
    
    experiences: List[WorkExperience] = Field(default_factory=list)
    education: List[Education] = Field(default_factory=list)
    projects: List[Project] = Field(default_factory=list)
    skills: SkillBank = Field(default_factory=SkillBank)
    
    # Knowledge vault / custom QA pairs learned or manually entered
    custom_qa: Dict[str, str] = Field(default_factory=dict)

class JobAnalysisRequest(BaseModel):
    job_title: Optional[str] = ""
    company_name: Optional[str] = ""
    job_url: Optional[str] = ""
    raw_text: str

class JobAnalysisResult(BaseModel):
    job_title: str
    company_name: str
    summary: str
    required_skills: List[str] = Field(default_factory=list)
    preferred_skills: List[str] = Field(default_factory=list)
    key_responsibilities: List[str] = Field(default_factory=list)
    ats_keywords: List[str] = Field(default_factory=list)
    tone_and_culture: str = ""

class CurateResumeRequest(BaseModel):
    job_analysis: JobAnalysisResult
    candidate_profile: Optional[CandidateProfile] = None

class CuratedResume(BaseModel):
    tailored_headline: str
    tailored_summary: str
    highlighted_skills: List[str]
    tailored_experiences: List[WorkExperience]
    tailored_projects: List[Project]
    ats_match_score: int
    matching_keywords: List[str]
    missing_keywords: List[str]
    suggested_cover_letter: str

class ScreeningQARequest(BaseModel):
    job_title: str
    company_name: str
    questions: List[str]

class ScreeningQAResponse(BaseModel):
    answers: Dict[str, str]
