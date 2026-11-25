# GhostTouchGuardian

**Stop ghost touches before they happen.**

GhostTouchGuardian is an Android accessibility service that blocks false capacitive touches (ghost touches) using multi-signal detection. It's specifically designed to handle S Pen electromagnetic interference that causes phantom touches after stylus use.

## ✨ Features

- **Smart Detection**: 3-tier voting system analyzes touch geometry, pressure, acceleration, and spatial jitter
- **S Pen Echo Blocking**: Prevents electromagnetic interference touches (50-500ms after stylus lift)
- **Burst Detection**: Blocks rapid repeated touches at the same location
- **Adaptive Learning**: Adjusts sensitivity based on your feedback
- **Safe Zones**: Define screen areas where touches are always allowed
- **24/7 Protection**: Foreground service with minimal battery impact
- **Privacy First**: No internet, no tracking, no data collection

## 📥 Installation

1. Download the latest APK from [Releases](../../releases)
2. Install the APK (allow "Install from unknown sources" if prompted)
3. Launch GhostTouchGuardian
4. Grant required permissions:
   - **Overlay permission**: Required to intercept touches
   - **Battery optimization exemption**: Recommended for 24/7 protection
5. Tap "Resume Guardian" to activate

## 🚀 Quick Start

### Basic Usage
1. **Enable Protection**: Tap "Resume Guardian" in the main screen
2. **Monitor Stats**: Enable "Stats Overlay" to see real-time blocking
3. **Report Mistakes**: Tap "Wrong?" in the notification if detection is incorrect

### S Pen Mode
If you use an S Pen stylus:
1. Tap "S-Pen Mode: OFF" to enable
2. This blocks **ALL** capacitive (finger) touches
3. Only S Pen touches are allowed
4. Useful for drawing/writing without palm rejection issues

### Safe Zones
Define screen areas where touches are never blocked (e.g., keyboard, navigation bar):
1. Tap "Configure Safe Zones" (coming soon - feature in development)
2. Drag rectangles over areas to protect
3. Saves automatically

## 🧠 How Detection Works

GhostTouchGuardian uses a **3-tier decision system**:

### Tier 1: Unconditional ALLOW
- Physical impact detected (accelerometer shows hand movement)
- Large, round, gradual pressure (flesh contact characteristics)
- Touch inside a safe zone
- S Pen stylus tool type

### Tier 2: Unconditional BLOCK  
- Burst pattern: rapid touches (<50ms) at same location
- S Pen echo: touch within 50-500ms after stylus lift, with low acceleration
- S Pen Mode active (finger touches blocked)

### Tier 3: Multi-Signal Voting
If neither tier 1 nor tier 2 applies, the system scores the touch using:
- **Spatial jitter**: Ghost touches have low jitter (<2.8px RMS), human touches have higher jitter (>3.5px)
- **Contact geometry**: Size, pressure, eccentricity (elongation)
- **Pressure slope**: Flesh has gradual ramp, water/EMI has instant spike
- **Acceleration**: Hand tremor vs stationary interference
- **Verdict**: 2+ human signals → ALLOW, 3+ ghost signals with 0 human → BLOCK

## 🔧 Troubleshooting

**Q: Legitimate touches are being blocked**
- Tap "Wrong?" button in the notification
- The app learns and adjusts sensitivity (makes detection more lenient)
- Create a safe zone over problematic areas

**Q: Ghost touches are getting through**
- Tap "Wrong?" when a ghost touch is allowed
- The app will become more strict
- Enable S Pen Mode if you're not using fingers

**Q: Stats overlay is in the way**
- Long-press and drag the stats overlay to reposition
- Or disable it: Tap "Stats Overlay: ON" to toggle off

**Q: Service stops after task manager kill**
- Grant battery optimization exemption
- Ensure "Appear on top" permission is granted
- Some manufacturers (Xiaomi, Oppo) need additional "Autostart" permission

## 📊 Detection Signals Explained

| Signal | Ghost Touch | Human Touch |
|--------|-------------|-------------|
| **Spatial Jitter** | <2.8px (stationary) | >3.5px (hand tremor) |
| **Dwell Time** | <100ms (brief spike) | >100ms (intentional) |
| **Pressure Slope** | Instant spike | Gradual ramp |
| **Eccentricity** | >2.4 (elongated track) | <1.9 (round) |
| **Accelerometer** | <0.016 m/s² (no impact) | >0.016 m/s² (hand movement) |
| **Size** | <0.03 (tiny point) | >0.03 (finger pad) |

## 🔒 Privacy

- **No internet permission** - Works 100% offline
- **No data collection** - Detection happens locally
- **No analytics** - No tracking, no telemetry
- **CSV logging** - Optional, stored locally only (for your own analysis)

## 🛠 For Developers

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for technical details and [USER_GUIDE.md](docs/USER_GUIDE.md) for complete usage instructions.

**Key Files:**
- G.kt - Detection logic (qDecide, decide, 3-tier voting)
- Svc.kt - Foreground service and overlay management
- Sen.kt - Accelerometer ring buffer
- Rec.kt - CSV recorder and adaptive threshold learning
- K.kt - All detection threshold constants

## 📝 License

This project is licensed under the MIT License - see [LICENSE](LICENSE) for details.

## 🤝 Contributing

Contributions welcome! Please ensure:
- Detection logic remains deterministic
- No breaking changes to existing thresholds
- Add tests for new features
- Follow existing code style (minified variable names, compact layout)

## ⚠ Disclaimer

This app is provided as-is for accessibility purposes. It may not catch all ghost touches or may occasionally block legitimate touches. Use the feedback system ("Wrong?" button) to improve accuracy over time.

---

**Version:** 4.1.0  
**Minimum Android:** 6.0 (API 23)  
**Tested on:** Galaxy Note 20 Ultra, Pixel 6, OnePlus 9  
**Author:** GTG Team

