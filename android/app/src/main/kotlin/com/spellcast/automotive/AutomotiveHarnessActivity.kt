package com.spellcast.automotive

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.spellcast.core.SpellcastGame

class AutomotiveHarnessActivity : AndroidApplication() {

    private lateinit var game: SpellcastGame
    private var safetyRestrictionManager: SafetyRestrictionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        game = SpellcastGame()

        val config = AndroidApplicationConfiguration().apply {
            useAccelerometer = false
            useCompass = false
            useGyroscope = false
            useImmersiveMode = true
        }

        initialize(game, config)

        safetyRestrictionManager = SafetyRestrictionManager(this) { isRestricted ->
            game.setDrivingRestricted(isRestricted)
        }.also { it.init() }
    }

    override fun onDestroy() {
        safetyRestrictionManager?.dispose()
        super.onDestroy()
    }
}
