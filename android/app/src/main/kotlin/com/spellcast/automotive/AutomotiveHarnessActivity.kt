package com.spellcast.automotive

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.spellcast.core.SpellcastGame

class AutomotiveHarnessActivity : AndroidApplication() {

    private var disposeSafetyObserver: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SpellcastGame().also { game ->
            initialize(
                game,
                AndroidApplicationConfiguration().apply {
                    useAccelerometer = false
                    useCompass = false
                    useGyroscope = false
                    useImmersiveMode = true
                }
            )
            disposeSafetyObserver = SafetyRestrictionManager.observe(this, game::setDrivingRestricted)
        }
    }

    override fun onDestroy() {
        disposeSafetyObserver?.invoke()
        disposeSafetyObserver = null
        super.onDestroy()
    }
}
