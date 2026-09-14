import os
import shutil
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from gradio_client import Client, handle_file

SPACE_ID = os.getenv("HF_SPACE", "r3gm/wan2-2-fp8da-aoti-preview2")

app = FastAPI(title="ImagenToVideoAI Backend", version="1.0.0")

_client: Client | None = None


def get_client() -> Client:
    global _client
    if _client is None:
        token = os.getenv("HF_TOKEN")
        _client = Client(SPACE_ID, token=token) if token else Client(SPACE_ID)
    return _client


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

    # El Space Wan 2.2 actual admite una duración limitada; mantenemos la interfaz
    # de la app flexible y recortamos de forma segura al máximo disponible.
    duration = max(2.0, min(float(duration), 5.0))

    workdir = Path(tempfile.mkdtemp(prefix="imagetovideoai-"))
    input_path = workdir / (image.filename or "input.jpg")

    try:
        with input_path.open("wb") as output:
            shutil.copyfileobj(image.file, output)

        client = get_client()

        # Orden de inputs del Space Wan 2.2 I2V actual. Los valores conservadores
        # reducen tiempo/coste de GPU manteniendo una calidad razonable.
        result = client.predict(
            handle_file(str(input_path)),
            None,
            prompt.strip(),
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
            24,
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

        # gradio_client normalmente devuelve un fichero descargado localmente.
        if str(video_path).startswith("http://") or str(video_path).startswith("https://"):
            import urllib.request
            downloaded = workdir / "generated.mp4"
            urllib.request.urlretrieve(str(video_path), downloaded)
            video_path = downloaded

        video_path = Path(video_path)
        if not video_path.exists():
            raise RuntimeError(f"No se encontró el vídeo generado: {video_path}")

        return FileResponse(
            video_path,
            media_type="video/mp4",
            filename="imagen-a-video.mp4",
            background=None,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Error generando el vídeo: {exc}") from exc
    finally:
        # No borramos inmediatamente el directorio si el resultado procede de él.
        # Render limpia /tmp automáticamente y evita acumular ficheros indefinidamente.
        pass

