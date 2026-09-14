# ImagenToVideoAI

Aplicación Android para convertir una imagen en un vídeo mediante IA.

## Funcionamiento

- Selección de imagen desde el dispositivo.
- Vista previa de la imagen.
- Prompt para describir el movimiento.
- Generación mediante Wan 2.2 Image-to-Video en un Hugging Face Space con ZeroGPU.
- Duración seleccionable de 4 o 5 segundos en la versión actual.
- Descarga del vídeo desde el backend y reproducción dentro de la app.
- Backend FastAPI desplegado en Render Free.
- GitHub Actions para compilar automáticamente la APK de debug.

## Arquitectura

`Android APK → Render FastAPI → Hugging Face Wan 2.2 → vídeo MP4 → Android APK`

No se necesita una API de pago. El backend no guarda claves de proveedor en la APK.

## Backend

Variables opcionales:

- `HF_SPACE`: Space de Hugging Face a utilizar. Por defecto: `r3gm/wan2-2-fp8da-aoti-preview2`.
- `HF_TOKEN`: token de Hugging Face opcional para acceder al Space.

## Build

El workflow de GitHub Actions compila `app-debug.apk` en cada push a `main`.
