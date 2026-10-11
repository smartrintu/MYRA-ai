# MYRA — Android AI Voice Assistant

## GitHub se APK banana
1. GitHub par naya repository banao (Public ya Private).
2. Is poore project ke files (`.github` folder ke saath) repo mein upload karo.
3. Repo ke "Actions" tab mein "Build APK" workflow khud chalega (ya "Run workflow" dabao).
4. 5-10 minute baad workflow ke page par neeche "Artifacts" mein `MYRA-debug-apk` milega. Download karo, unzip karo, `app-debug.apk` phone mein install karo.

## First run
- Gemini API key: https://aistudio.google.com/apikey -> app Settings mein daalo.
- Mic, contacts, phone permissions allow karo.
- Settings mein Accessibility aur "Display over other apps" ON karo.
- Model IDs: app/src/main/java/com/myra/assistant/ai/GeminiModels.kt

## Full control ke liye zaroori
- Settings > Accessibility mein MYRA ko ON karo (YouTube auto-play, WhatsApp send, tap, screen read ke liye).
- Agar Android "Restricted setting" bole: Phone Settings > Apps > MYRA > (teen dot menu) > "Allow restricted settings", phir Accessibility ON karo.
- Gaana chalate hi YouTube khulta hai aur MYRA background mein chali jaati hai; Android background mein mic nahi sunne deta, wapas MYRA kholo.
