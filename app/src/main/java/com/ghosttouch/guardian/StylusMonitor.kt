package com.ghosttouch.guardian

/** Smart echo window that only activates when stylus recently used (v4.2.0). */
object StylusMonitor {
    @Volatile private var lastStylusTime = 0L
    @Volatile private var stylusUsageCount = 0
    
    fun recordStylusTouch(time: Long) {
        lastStylusTime = time
        stylusUsageCount++
        St.lSty = time
        L.d(Tags.DETECTION, "Stylus recorded: usage=$stylusUsageCount")
    }
    
    fun isEchoRiskActive(currentTime: Long): Boolean {
        if (stylusUsageCount == 0) return false
        
        val timeSinceStylus = currentTime - lastStylusTime
        return when {
            timeSinceStylus < 2000L -> true
            timeSinceStylus < 10000L -> true
            stylusUsageCount > 10 && timeSinceStylus < 30000L -> true
            else -> false
        }
    }
    
    fun getEchoWindow(currentTime: Long): LongRange? {
        return if (isEchoRiskActive(currentTime)) {
            K.E0 .. K.E1
        } else {
            null
        }
    }
    
    fun reset() {
        lastStylusTime = 0L
        stylusUsageCount = 0
    }
}
