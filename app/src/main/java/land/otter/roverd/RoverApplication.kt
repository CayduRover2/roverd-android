package land.otter.roverd

import androidx.multidex.MultiDexApplication
import com.serenegiant.utils.UVCUtils

class RoverApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        RoverRuntimeState.initialize(this)
        UVCUtils.init(this)
        CrashLogger.install(this)
        RoverAudioController.initialize(this)
        HeadlightController.initialize(this)
        RoverRuntimeState.log("APPLICATION onCreate complete")
    }
}
