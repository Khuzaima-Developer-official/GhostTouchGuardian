# GhostTouchGuardian User Guide

## Getting Started

### First Launch
1. Install and open GhostTouchGuardian
2. Grant "Appear on top" permission when prompted
3. Tap "Resume Guardian" to activate protection
4. Test by touching screen - legitimate touches should work normally

### Battery Optimization
For 24/7 protection, disable battery optimization:
1. Tap "Allow 24/7 unrestricted" button
2. Select "Don't optimize" when Android asks
3. This prevents system from killing the service

## Features

### Guardian Mode (Standard Protection)
- Blocks ghost touches automatically
- Uses multi-signal detection (jitter, pressure, acceleration, geometry)
- Learns from your feedback ("Wrong?" button)
- Allows all intentional touches

**When to use:** Daily usage on any device

### S-Pen Mode (Stylus-Only)
- Blocks ALL finger touches
- Only S Pen stylus touches allowed
- Useful for precise drawing/writing without palm rejection

**When to use:** 
- Using Samsung S Pen for drawing/note-taking
- Want zero finger interference
- Digital art, signatures, detailed work

**Toggle:** Tap "S-Pen Mode: OFF/ON" in main screen or notification

### Stats Overlay
- Shows real-time detection verdicts
- Displays: verdict (ALLOW/BLOCK), scores (h/g), reason, total blocked count
- Draggable - long-press and move to reposition

**Toggle:** Tap "Stats Overlay: OFF/ON" in main screen or notification

### Feedback System ("Wrong?" Button)
When detection makes a mistake:
1. Tap "Wrong?" in notification immediately
2. App adjusts sensitivity automatically
3. If ghost touch was allowed → becomes more strict
4. If real touch was blocked → becomes more lenient

**Tip:** Use within a few seconds of the mistake for best results

## Understanding Detection

### How It Works

**Tier 1: Always Allow**
- Physical impact detected (hand movement via accelerometer)
- Large, round touch with normal pressure (flesh characteristics)
- Touch inside safe zone
- S Pen stylus detected

**Tier 2: Always Block**
- Burst pattern: Rapid touches (<50ms apart) at same spot
- S-Pen electromagnetic echo: Touch 50-500ms after stylus lift-off

**Tier 3: Scoring**
If neither tier 1 nor tier 2 matches, the app scores the touch:
- 6 human signals analyzed (tremor, size, pressure, etc.)
- 7 ghost signals analyzed (stability, shape, timing, etc.)
- 2+ human signals → ALLOW
- 3+ ghost signals with 0 human → BLOCK
- Otherwise → ALLOW (defaults to allowing)

### Detection Signals Explained

| Signal | Ghost Touch | Real Touch |
|--------|-------------|------------|
| Spatial Jitter | Very stable (<2.8px) | Natural tremor (>3.5px) |
| Dwell Time | Brief spike (<100ms) | Intentional press (>100ms) |
| Pressure | Instant or extreme | Gradual ramp |
| Shape | Elongated track | Round finger pad |
| Acceleration | No impact | Hand movement detected |
| Size | Tiny point | Normal finger size |

### Why Touches Get Blocked

Common reasons shown in stats overlay:
- **Echo:XXms** - S-Pen electromagnetic interference
- **Burst:XXms** - Rapid repeat at same location
- **gVote:X** - Ghost score >= 3 (scored as interference)
- **Spike@DOWN** - Extreme shape/pressure at first contact

### Why Touches Get Allowed

Common reasons:
- **Stylus** - S Pen tool type detected
- **SafeZone** - Touch in protected area
- **Accel:X** - Physical impact detected
- **Flesh** - Normal finger contact
- **hVote:X** - Human score >= 2
- **PassThrough** - Uncertain, defaulted to allow

## Troubleshooting

### My Real Touches Are Being Blocked

**Solutions:**
1. **Use Feedback**: Tap "Wrong?" immediately after block - app learns
2. **Check S-Pen Mode**: If ON, all finger touches are blocked - toggle OFF
3. **Create Safe Zone** (when UI available): Protect keyboard, buttons, etc.
4. **Verify Accelerometer**: Try tapping with more force (creates impact signal)

**Common Causes:**
- Very light/slow touches (no acceleration detected)
- Wet fingers (unusual pressure/geometry)
- Screen protector affecting touch properties

### Ghost Touches Are Getting Through

**Solutions:**
1. **Use Feedback**: Tap "Wrong?" when ghost allowed - app becomes stricter
2. **Enable S-Pen Mode**: If not using fingers, block all capacitive input
3. **Check Detection**: Enable stats overlay to see why ghosts are allowed

**Common Causes:**
- Ghost touch mimics human characteristics (rare)
- Thresholds adjusted too lenient from feedback
- Water creates physical impact (triggers accelerometer)

### Stats Overlay Is In The Way

**Solutions:**
1. **Reposition**: Long-press stats overlay and drag to corner
2. **Disable**: Tap "Stats Overlay: ON" to toggle OFF
3. **Notification Only**: View stats in notification instead

### Service Keeps Stopping

**Solutions:**
1. **Battery Optimization**: Tap "Allow 24/7 unrestricted" in app
2. **Overlay Permission**: Verify "Appear on top" is granted
3. **Manufacturer Settings**:
   - Xiaomi: Security → Autostart → Enable for GTG
   - Oppo/OnePlus: Battery → App Battery Management → GTG → Don't optimize
   - Samsung: Usually works without extra settings

### High Battery Drain

**Check:**
1. **Normal Usage**: <2% per day is expected
2. **Excessive Logging**: Check if debug logging is enabled (should be OFF in release)
3. **Service Running**: If not using app, pause Guardian to save battery

## S-Pen Mode Details

### When To Use
- Drawing, sketching, digital art
- Note-taking with stylus
- Signatures requiring precision
- Any time palm/finger touches interfere

### How It Works
1. Enable: Tap "S-Pen Mode: OFF" to turn ON
2. App blocks ALL capacitive (finger) touches
3. Only stylus (tool type TOOL_TYPE_STYLUS) is allowed
4. Notification shows "🖊 S-Pen exclusive"

### Electromagnetic Echo Blocking
Even with S-Pen Mode OFF, the app detects EM echoes:
- After lifting stylus, electromagnetic field decays
- Causes phantom capacitive touches for ~50-500ms
- App detects timing + low acceleration + brief dwell → blocks echo
- Real touches outside echo window are allowed

## Safe Zones (Coming Soon)

**Purpose:** Define screen areas where touches are ALWAYS allowed, bypassing detection.

**Use Cases:**
- Keyboard area (avoid blocking typing)
- Navigation buttons
- App-specific controls

**How To Use** (once UI is built):
1. Tap "Configure Safe Zones" in main screen
2. Drag rectangles over areas to protect
3. Zones saved automatically
4. Touches inside zones always allowed

## Advanced: Adaptive Thresholds

The app learns from your feedback and adjusts two key thresholds:

**Jitter Ghost Max (aJG):**
- Base: 2.8px RMS
- When you report false block → increases (more lenient)
- Range: 2.0px to 3.6px

**Jitter Human Min (aJH):**
- Base: 3.5px RMS  
- When you report false allow → increases (more strict on allowing)
- Range: 2.8px to 4.5px

**Gap Maintained:** aJH always > aJG (ensures clear separation)

**Reset:** Uninstall/reinstall app to reset to defaults (future: manual reset button)

## CSV Logs (For Analysis)

The app logs all detections to CSV file (optional, for your analysis only):

**Location:** Android/data/com.ghosttouch.guardian/files/gtg_log.csv

**Columns:** time, verdict, h_score, g_score, jitter, acceleration, dwell, size, pressure, eccentricity, reason

**Use Cases:**
- Debugging false detections
- Understanding your usage patterns
- Sharing with developer for improvements

**Privacy:** Logs are LOCAL only, never uploaded anywhere

## Tips & Best Practices

1. **Enable Stats Overlay Initially**: Watch how detection works, learn patterns
2. **Use Feedback Liberally**: App improves with more feedback
3. **S-Pen Users**: Keep S-Pen Mode OFF normally, ON only when drawing
4. **Create Safe Zones**: For areas with frequent false blocks (once UI available)
5. **Battery Optimization**: Always exempt GTG for consistent protection
6. **Screen Protectors**: May affect touch properties - give feedback if issues occur

## FAQ

**Q: Does the app collect my data?**  
A: No. No internet permission, all processing local, CSV logs stay on device.

**Q: Why does it need overlay permission?**  
A: To intercept touches before they reach other apps (required for blocking).

**Q: Can I use it with games?**  
A: Yes, but test first - some games may have unusual touch patterns.

**Q: Does it work with all styluses?**  
A: Echo detection optimized for Samsung S Pen (Wacom EMR). Other styluses work but may not have echo issues.

**Q: What about Xiaomi/Oppo ghost touch issues?**  
A: App works, but may need manufacturer-specific autostart permissions.

**Q: Can I whitelist specific apps?**  
A: Not yet - safe zones (screen areas) are coming soon.

**Q: Battery impact?**  
A: ~1-2% per day with 24/7 protection.

## Support

**Issues:** Report on GitHub (include CSV logs if possible)  
**Feature Requests:** GitHub Issues  
**Privacy Policy:** See PRIVACY_POLICY.md
