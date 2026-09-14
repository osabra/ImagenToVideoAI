import os
import shutil
import tempfile
import threading
import uuid
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from gradio_client import Client, handle_file

SPACE_ID = os.getenv("HF_SPACE", "r3gm/wan2-2-fp8da-aoti-preview2")

app = FastAPI(title="ImagenToVideoAI Backend", version="1.1.0")
_client: Client | None = None
_jobs: dict[str, dict[str, Any]] = {}
_jobs_lock = threading.Lock()


def get_client() -> Client:
    global _client
    if _client is None:
        token = os.getenv("HF_TOKEN")
        _client = Client(SPACE_ID, token=token) if token else Client(SPACE_ID)
    return _client


def set_job(job_id: str, **values: Any) -> None:
    with _jobs_lock:
        _jobs[job_id].update(values)


def run_generation(job_id: str, input_path: Path, prompt: str, duration: float) -> None:
    try:
        set_job(job_id, status="generating", message="Generando vídeo con Wan 2.2…")
        client = get_client()

        # Signature verificada contra la versión actual del Space Wan 2.2.
        # frame_multiplier debe ser 1-6; 1 evita una interpolación innecesaria.
        result = client.predict(
            handle_file(str(input_path)),
            None,
            prompt,
            6,
            "",
            duration,
            1.0,
            1.0,
            42,
            True,
            6,
            "UniPCMultistep",
            3.0,
            1,
            "4x-UltraSharp",
            1.0,
            True,
            True,
            True,
            api_name="/generate_video",
        )

        video_result = result[0] if isinstance(result, tuple) else result
        video_path = None
        if isinstance(video_result, str):
            video_path = video_result
        elif isinstance(video_result, dict):
            video_path = video_result.get("path") or video_result.get("url")
        elif hasattr(video_result, "path"):
            video_path = video_result.path
        elif hasattr(video_result, "url"):
            video_path = video_result.url

        if not video_path:
            raise RuntimeError(f"El proveedor no devolvió un vídeo: {result!r}")

        if str(video_path).startswith(("http://", "https://")):
            import urllib.request
            downloaded = input_path.parent / "generated.mp4"
            urllib.request.urlretrieve(str(video_path), downloaded)
            video_path = downloaded

        video_path = Path(video_path)
        if not video_path.exists():
            raise RuntimeError(f"No se encontró el vídeo generado: {video_path}")

        set_job(job_id, status="completed", message="Vídeo generado correctamente.", video_path=str(video_path))
    except Exception as exc:
        set_job(job_id, status="failed", message=f"Error generando el vídeo: {exc}")


@app.get("/health")
def health():
    return {"ok": True, "provider": SPACE_ID}


@app.post("/generate")
async def generate(
    image: UploadFile = File(...),
    prompt: str = Form(...),
    duration: float = Form(3.5),
):
    if not prompt.strip():
        raise HTTPException(status_code=400, detail="El prompt no puede estar vacío")

    # El Space actual admite aproximadamente 0.5–10 s; 4-5 s es un rango práctico.
    duration = max(2.0, min(float(duration), 5.0))
    job_id = uuid.uuid4().hex
    workdir = Path(tempfile.mkdtemp(prefix=f"imagentovideoai-{job_id}-"))
    input_path = workdir / (image.filename or "input.jpg")

    try:
        with input_path.open("wb") as output:
            shutil.copyfileobj(image.file, output)
    except Exception as exc:
        shutil.rmtree(workdir, ignore_errors=True)
        raise HTTPException(status_code=400, detail=f"No se pudo guardar la imagen: {exc}") from exc

    with _jobs_lock:
        _jobs[job_id] = {
            "status": "queued",
            "message": "Trabajo recibido.",
            "video_path": None,
        }

    thread = threading.Thread(
        target=run_generation,
        args=(job_id, input_path, prompt.strip(), duration),
        daemon=True,
    )
    thread.start()

    return {"job_id": job_id, "status": "queued", "status_url": f"/status/{job_id}"}


@app.get("/status/{job_id}")
def status(job_id: str):
    with _jobs_lock:
        job = _jobs.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Trabajo no encontrado")
    response = {"job_id": job_id, "status": job["status"], "message": job["message"]}
    if job.get("status") == "completed":
        response["video_url"] = f"/video/{job_id}"
    return response


@app.get("/video/{job_id}")
def video(job_id: str):
    with _jobs_lock:
        job = _jobs.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Trabajo no encontrado")
    if job.get("status") != "completed":
        raise HTTPException(status_code=409, detail="El vídeo todavía no está listo")

    path = Path(job["video_path"])
    if not path.exists():
        raise HTTPException(status_code=410, detail="El vídeo ya no está disponible")
    return FileResponse(path, media_type="video/mp4", filename="imagen-a-video.mp4")
