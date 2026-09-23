import io
import base64
from typing import Tuple, Optional
from pypdf import PdfReader
import docx
from PIL import Image

class ResumeParserService:
    @staticmethod
    def extract_text_from_pdf(file_bytes: bytes) -> str:
        """Extract plain text from PDF bytes."""
        reader = PdfReader(io.BytesIO(file_bytes))
        text_parts = []
        for i, page in enumerate(reader.pages):
            page_text = page.extract_text() or ""
            if page_text.strip():
                text_parts.append(page_text.strip())
        return "\n\n--- Page Break ---\n\n".join(text_parts)

    @staticmethod
    def extract_text_from_docx(file_bytes: bytes) -> str:
        """Extract plain text from DOCX file bytes."""
        doc = docx.Document(io.BytesIO(file_bytes))
        text_parts = []
        for paragraph in doc.paragraphs:
            if paragraph.text.strip():
                text_parts.append(paragraph.text.strip())
        for table in doc.tables:
            for row in table.rows:
                row_text = [cell.text.strip() for cell in row.cells if cell.text.strip()]
                if row_text:
                    text_parts.append(" | ".join(row_text))
        return "\n".join(text_parts)

    @staticmethod
    def process_image(file_bytes: bytes) -> Tuple[str, str]:
        """Validate image and convert to base64 for LLM Vision analysis. Returns (mime_type, base64_str)."""
        image = Image.open(io.BytesIO(file_bytes))
        format_name = image.format.lower() if image.format else "png"
        mime_type = f"image/{format_name}" if format_name in ["png", "jpeg", "webp"] else "image/png"
        
        # Buffer as standard JPEG/PNG
        buffer = io.BytesIO()
        image.save(buffer, format=image.format or "PNG")
        b64_str = base64.b64encode(buffer.getvalue()).decode("utf-8")
        return mime_type, b64_str

    @classmethod
    def parse_file(cls, filename: str, file_bytes: bytes) -> Tuple[str, Optional[Tuple[str, str]]]:
        """
        Determines file type and extracts content.
        Returns:
            (extracted_text, image_tuple)
            where image_tuple is (mime_type, base64_str) if it was an image.
        """
        lower_name = filename.lower()
        if lower_name.endswith(".pdf"):
            text = cls.extract_text_from_pdf(file_bytes)
            return text, None
        elif lower_name.endswith(".docx") or lower_name.endswith(".doc"):
            text = cls.extract_text_from_docx(file_bytes)
            return text, None
        elif any(lower_name.endswith(ext) for ext in [".png", ".jpg", ".jpeg", ".webp", ".bmp"]):
            mime_type, b64 = cls.process_image(file_bytes)
            return "[Image-based Resume: Multimodal extraction required]", (mime_type, b64)
        else:
            # Fallback to UTF-8 decoding
            try:
                return file_bytes.decode("utf-8"), None
            except Exception:
                raise ValueError(f"Unsupported file format: {filename}. Please upload PDF, DOCX, or Image (PNG/JPG).")
