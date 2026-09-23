import datetime
from fastapi import APIRouter, UploadFile, File, Form, HTTPException, Response
from typing import Dict, Any, List

from app.models.profile import (
    CandidateProfile,
    JobAnalysisRequest,
    JobAnalysisResult,
    CurateResumeRequest,
    CuratedResume,
    ScreeningQARequest,
    ScreeningQAResponse
)
from app.services.profile_store import ProfileStore
from app.services.parser_service import ResumeParserService
from app.services.ai_service import AIService
from app.services.resume_export_service import ResumeExportService

router = APIRouter()

@router.get("/profile", response_model=CandidateProfile)
async def get_profile():
    return ProfileStore.get_profile()

@router.put("/profile", response_model=CandidateProfile)
async def update_profile(profile: CandidateProfile):
    return ProfileStore.save_profile(profile)

@router.post("/resume/upload", response_model=CandidateProfile)
async def upload_resume(file: UploadFile = File(...)):
    """
    Accepts PDF, DOCX, or Image (PNG, JPG, WEBP) resume files.
    Extracts text/image data and parses it into a CandidateProfile.
    """
    try:
        content = await file.read()
        extracted_text, image_tuple = ResumeParserService.parse_file(file.filename, content)
        
        parsed_profile = await AIService.parse_resume_to_profile(
            raw_text=extracted_text,
            image_tuple=image_tuple
        )
        
        # Save to store
        ProfileStore.save_profile(parsed_profile)
        return parsed_profile
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Failed to parse resume: {str(e)}")

@router.post("/job/analyze", response_model=JobAnalysisResult)
async def analyze_job(request: JobAnalysisRequest):
    return await AIService.analyze_job_description(
        raw_text=request.raw_text,
        job_title=request.job_title or "",
        company_name=request.company_name or ""
    )

@router.post("/resume/curate", response_model=CuratedResume)
async def curate_resume(request: CurateResumeRequest):
    profile = request.candidate_profile or ProfileStore.get_profile()
    return await AIService.curate_resume_and_skills(
        profile=profile,
        job_analysis=request.job_analysis
    )

@router.post("/screening/answers", response_model=ScreeningQAResponse)
async def generate_screening_answers(request: ScreeningQARequest):
    profile = ProfileStore.get_profile()
    answers = await AIService.answer_screening_questions(
        profile=profile,
        job_title=request.job_title,
        company_name=request.company_name,
        questions=request.questions
    )
    return ScreeningQAResponse(answers=answers)

@router.post("/resume/export/pdf")
async def export_pdf(curated: CuratedResume):
    profile = ProfileStore.get_profile()
    pdf_bytes = ResumeExportService.generate_pdf(profile, curated)
    return Response(
        content=pdf_bytes,
        media_type="application/pdf",
        headers={"Content-Disposition": f"attachment; filename=Resume_{profile.last_name}_Tailored.pdf"}
    )

@router.post("/resume/export/docx")
async def export_docx(curated: CuratedResume):
    profile = ProfileStore.get_profile()
    docx_bytes = ResumeExportService.generate_docx(profile, curated)
    return Response(
        content=docx_bytes,
        media_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        headers={"Content-Disposition": f"attachment; filename=Resume_{profile.last_name}_Tailored.docx"}
    )

@router.get("/applications")
async def get_applications():
    return ProfileStore.get_applications()

@router.post("/applications/log")
async def log_application(data: Dict[str, Any]):
    record = {
        "id": f"app-{int(datetime.datetime.now().timestamp())}",
        "timestamp": datetime.datetime.now().isoformat(),
        "company": data.get("company", "Unknown"),
        "role": data.get("role", "Unknown"),
        "url": data.get("url", ""),
        "status": data.get("status", "Autofilled - Ready for Review"),
        "ats_match_score": data.get("ats_match_score", 0)
    }
    return ProfileStore.log_application(record)
