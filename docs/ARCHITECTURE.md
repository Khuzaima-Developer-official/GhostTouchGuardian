# GhostTouchGuardian Architecture

## System Overview

GhostTouchGuardian is a foreground service that intercepts touch events via an overlay window, analyzes them using multi-signal detection, and either allows or blocks them based on ghost touch classification.

[Architecture diagram showing: User Touch → Ovr (Overlay) → Detection Logic (qDecide/decide) → Verdict → Rec (Recorder) + StatsOvr]

## Components

### UI.kt - Main Activity
Settings screen, permission management, service control

### Svc.kt - Foreground Service  
Service lifecycle, overlay management, notification, verdict callbacks

### G.kt - Detection Logic
- qDecide(): ACTION_DOWN instant decision
- decide(): ACTION_UP full analysis with 3-tier voting
- sj(): Spatial jitter calculation
- pSlp(): Pressure slope

### Sen.kt - Accelerometer
Ring buffer (128 samples), peak detection

### Rec.kt - Recorder
CSV logging, adaptive thresholds (aJG, aJH), user feedback

### P.kt - Preferences
SharedPreferences wrapper

### Z.kt - Safe Zones
Rectangle model, JSON serialization

### K.kt - Constants
All detection thresholds

## Detection Algorithm

### Tier 1: Unconditional ALLOW
Safe zone, accelerometer spike, flesh contact, pressure ramp

### Tier 2: Unconditional BLOCK
S-Pen echo (50-500ms), burst pattern

### Tier 3: Multi-Signal Voting
6 human signals, 7 ghost signals, adaptive thresholds

## Data Flow
ACTION_DOWN → qDecide → Block/Allow → CtxBuilder → ACTION_UP → decide → Verdict → Record → Stats
