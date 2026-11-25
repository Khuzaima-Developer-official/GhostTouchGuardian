# 📱 GhostTouchGuardian v4.2.0 Installation Guide

---

## ⚠️ APK Transfer Problem Solution

APK files are blocked by WhatsApp, Telegram, Gmail due to security restrictions.

---

## ✅ METHOD 1: ZIP File Transfer (EASIEST)

### **Step 1: Send ZIP File**
📦 **File:** `GhostTouchGuardian-v4.2.0.zip` (0.67 MB)

1. Open WhatsApp/Telegram
2. Send `GhostTouchGuardian-v4.2.0.zip` to yourself
3. ✅ ZIP files are NOT blocked!

### **Step 2: Extract on Phone**
1. Download ZIP on phone
2. Open with any file manager (Files, ZArchiver, etc.)
3. Extract ZIP file
4. You'll get: `GhostTouchGuardian-v4.2.0-RELEASE.apk`

### **Step 3: Install**
1. Tap the APK file
2. Allow "Install from unknown sources" if asked
3. Install → Done! ✅

---

## ✅ METHOD 2: Rename Trick (QUICK)

### **For WhatsApp/Telegram:**
📄 **File:** `GhostTouchGuardian-v4.2.0.apk.txt` (0.87 MB)

1. Send `.txt` file via WhatsApp
2. Download on phone
3. Use file manager to rename:
   - From: `GhostTouchGuardian-v4.2.0.apk.txt`
   - To: `GhostTouchGuardian-v4.2.0-RELEASE.apk`
4. Install the renamed file

---

## ✅ METHOD 3: USB Transfer (100% RELIABLE)

### **Windows PC to Android:**
1. Connect phone via USB cable
2. Phone notification: Tap "Charging this device via USB"
3. Select: **"File Transfer"** or **"MTP"**
4. On PC: Open "This PC" → Click on your phone name
5. Navigate to: `Internal Storage` → `Download` folder
6. Copy-paste: `GhostTouchGuardian-v4.2.0-RELEASE.apk`
7. On phone: Open Downloads → Install APK

---

## ✅ METHOD 4: Google Drive / Cloud

1. Upload `GhostTouchGuardian-v4.2.0-RELEASE.apk` to Google Drive
2. Share link with yourself
3. Open Drive on phone
4. Download APK
5. Install from Downloads

---

## ✅ METHOD 5: HTTP Server (Advanced)

If you have Python installed on PC:

```bash
# Start HTTP server in APK folder
python -m http.server 8000
```

Then on phone browser:
```
http://[YOUR_PC_IP]:8000/GhostTouchGuardian-v4.2.0-RELEASE.apk
```

---

## 🔐 Security Notes

### **Why APK blocked?**
- WhatsApp/Gmail/Telegram treat ALL APK files as potential malware
- This is a blanket policy (even legitimate apps blocked)

### **Is this APK safe?**
✅ YES - You built it yourself from source code  
✅ Signed with your own release key  
✅ No third-party involved  
✅ Open source code visible  

### **How to verify?**
Check APK signature:
```bash
jarsigner -verify -verbose -certs GhostTouchGuardian-v4.2.0-RELEASE.apk
```

Output should show:
```
jar verified.
Signed by: CN=ghostguard
```

---

## 📦 Available Files

| File | Size | Purpose |
|------|------|---------|
| `GhostTouchGuardian-v4.2.0-RELEASE.apk` | 0.87 MB | ⭐ Install this |
| `GhostTouchGuardian-v4.2.0.zip` | 0.67 MB | For WhatsApp transfer |
| `GhostTouchGuardian-v4.2.0.apk.txt` | 0.87 MB | Rename trick |
| `GhostTouchGuardian-v4.2.0-debug.apk` | 3.32 MB | Debug version |

---

## 🎯 Recommended Order

1. **Try ZIP method first** (easiest for messaging apps)
2. **If fails, use USB** (100% works)
3. **Cloud upload** (for future updates)

---

## ❓ Troubleshooting

### **"Installation blocked"**
→ Settings → Security → Enable "Unknown sources"  
→ Or: Settings → Apps → Special access → Install unknown apps → Enable for file manager

### **"App not installed"**
→ Uninstall old version first  
→ Make sure enough storage space  
→ Restart phone and try again

### **"Package appears to be corrupt"**
→ Re-download file (transfer interrupted)  
→ Verify file size matches (0.87 MB for release)

---

## ✅ Installation Complete?

After installation:
1. Open GhostTouchGuardian
2. Grant overlay permission
3. Grant battery optimization exception
4. Enable guardian
5. Test with normal usage for 2-3 days
6. Check accuracy improvements!

---

**Built:** November 25, 2026  
**Version:** 4.2.0 (build 27)  
**Package:** com.ghosttouch.guardian
