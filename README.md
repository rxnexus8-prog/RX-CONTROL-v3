# RX CONTROL ZONE — Android APK

AI-powered phone controller. WebView UI + Accessibility Service + Shizuku ADB bridge.

## Termux se GitHub push karke APK banao

```bash
# 1. Termux mein git setup karo (ek baar)
pkg install git -y
git config --global user.name "R4XGAMEZ"
git config --global user.email "your@email.com"

# 2. GitHub pe naya repo banao: RX-Control-Zone
#    github.com → New repository → RX-Control-Zone → Create

# 3. Ye folder Termux mein copy karo phir push karo
cd /sdcard/Download/rxcz_apk        # ya jahan tune save kiya
git init
git add .
git commit -m "initial: RX Control Zone APK"
git branch -M main
git remote add origin https://github.com/R4XGAMEZ/RX-Control-Zone.git
git push -u origin main
```

Push hote hi GitHub Actions automatically APK build karega (~3-5 min).

## APK download karo

1. GitHub repo → Actions tab
2. Latest workflow run pe click karo
3. Neeche "Artifacts" section mein `RX-Control-Zone-Debug` download karo
4. ZIP extract karo → `app-debug.apk` install karo

## APK install karne ke baad

1. App kholo → Setup screen aayegi
2. **Accessibility** on karo (Step 1 button)
3. **Shizuku** install karo Play Store se, Wireless ADB mode enable karo
4. Termux mein Python script chala do: `python rx_control_zone.py`
5. App mein Settings → ADB IP field mein `127.0.0.1:7070` daalo

## Files

```
app/src/main/
  assets/index.html              ← WebView UI (HTML)
  java/com/r4x/rxcontrolzone/
    MainActivity.kt              ← WebView + JS Bridge
    RxAccessibilityService.kt   ← UI element finder (no coordinates)
    BridgeClient.kt             ← TCP socket to Python
    BridgeService.kt            ← Foreground service
  AndroidManifest.xml           ← All permissions
  res/xml/accessibility_service_config.xml
.github/workflows/build.yml     ← Auto APK builder
```
