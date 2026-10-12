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

## Background / lock screen mein kaam karne ke liye
- MYRA ab ek foreground service (notification "MYRA sun rahi hai") se chalti hai; band karne ke liye notification ka **Stop** dabao.
- Settings mein: Accessibility ON, Display over other apps ON, aur **Battery: unrestricted** (tap karke Allow).
- Xiaomi/Redmi/POCO: App info > Autostart ON; Battery saver > "No restrictions"; Other permissions > "Display pop-up windows while running in background" ON; Recents mein MYRA ko lock (pin) karo.
- App sirf tab start hoti hai jab MYRA screen par khuli ho (Android ka rule). Ek baar start hone ke baad background/lock mein chalti rehti hai.
- PIN/pattern/fingerprint lock Android kisi app ko bypass nahi karne deta; "unlock_phone" sirf screen jagata hai aur swipe-only lock hata sakta hai.

## Naya: cute UI, GF style aur website builder
- Cute animated ladki (aankhein jhapakti hain, hothon se bolti hai, tap karne par wink + hearts), floating hearts, pink theme.
- Website: bolo "mere liye ek photography studio ki website bana do" — MYRA ~1 minute mein poori website banakar preview kholti hai (Share / Download buttons).
  PRO/TRIAL key chahiye. Website Download/MYRA folder mein bhi save hoti hai; GitHub Pages ya Netlify Drop se free publish kar sakte ho.

## Naya (v3)
- Avatar: semi-realistic drawn portrait + **real photo** option (Settings > "REAL PHOTO CHUNO"). Photo mein saans, glow, hearts animation aati hai.
- Multicolor theme: bolo "interface ka color nila kar do" ya "multicolor kar do" (rainbow animation).
- Contact page (Settings > CONTACT) aur Activation screen par bhi "CONTACT KARO" button.

## Naya (v4)
- Shayari mode hata diya gaya (button, setting aur auto-shayari sab).
- Cute girl default photo: image ko `app/src/main/res/drawable-nodpi/myra_girl.jpg` naam se rakho, app use default avatar bana legi (Settings se apni photo chunoge to wo upar rahegi).
- MYRA ke jawab tez: mic chunk 40 ms, turn detection fast (silence 350 ms), low-latency speaker, chhote jawab, kam waiting in actions.
- **Live terminal**: background me MYRA jo bhi command chalati hai ya website banati hai (code likhte hue live), wo main screen ke neeche terminal panel me dikhta hai. ✕ se band karo; kaam khatam hone ke 12 sec baad apne aap chhup jata hai.
