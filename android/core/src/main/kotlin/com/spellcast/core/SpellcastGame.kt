package com.spellcast.core

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Timer
import com.spellcast.core.display.AsciiDisplay
import com.spellcast.core.engine.GameSession
import com.spellcast.core.persistence.GdxPreferencesKeyValueStore

/**
 * Core LibGDX game entrypoint wiring [AsciiDisplay], [GameSession], dictionary loading,
 * touch/keyboard input, and AAOS driving safety lockout overlay.
 */
class SpellcastGame : ApplicationAdapter() {

    private data class RuntimeContext(
        val batch: SpriteBatch,
        val camera: OrthographicCamera,
        val display: AsciiDisplay,
        val session: GameSession
    )

    private var ctx: RuntimeContext? = null
    private var isDrivingRestricted: Boolean = false

    private val inputProcessor = object : InputAdapter() {
        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (isDrivingRestricted) return true
            return ctx?.session?.handleTouch(screenX, screenY) ?: false
        }

        override fun keyDown(keycode: Int): Boolean {
            if (isDrivingRestricted) return true
            return ctx?.session?.handleKeyDown(keycode) ?: false
        }
    }

    override fun create() {
        Gdx.graphics.isContinuousRendering = false
        val display = AsciiDisplay("SpellyFreeMono.ttf").apply { init() }
        val dictionary = loadDictionary()
        val session = GameSession(
            display = display,
            store = GdxPreferencesKeyValueStore(),
            dictionary = dictionary,
            scheduleDelay = { delayMs, action ->
                Timer.schedule(
                    object : Timer.Task() {
                        override fun run() {
                            if (!isDrivingRestricted) {
                                action()
                            }
                        }
                    },
                    delayMs / 1000f
                )
            },
            requestRender = { Gdx.graphics.requestRendering() }
        )

        ctx = RuntimeContext(
            batch = SpriteBatch(),
            camera = OrthographicCamera().apply {
                setToOrtho(false, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
            },
            display = display,
            session = session
        )
        Gdx.input.inputProcessor = inputProcessor
        refreshScreen()
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        ctx?.let { runtime ->
            runtime.camera.setToOrtho(false, width.toFloat(), height.toFloat())
            runtime.camera.update()
            runtime.batch.projectionMatrix = runtime.camera.combined
            runtime.display.resize(width, height)
            refreshScreen()
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ctx?.let { runtime ->
            runtime.camera.update()
            runtime.batch.projectionMatrix = runtime.camera.combined
            runtime.batch.begin()
            runtime.display.render(runtime.batch)
            runtime.batch.end()
        }
    }

    fun setDrivingRestricted(restricted: Boolean) {
        if (isDrivingRestricted == restricted) return
        Gdx.app?.postRunnable {
            isDrivingRestricted = restricted
            Gdx.input.inputProcessor = if (restricted) null else inputProcessor
            refreshScreen()
        }
    }

    fun showTitle() {
        ctx?.session?.showTitle()
    }

    private fun refreshScreen() {
        val runtime = ctx ?: return
        if (isDrivingRestricted) {
            renderDrivingRestrictionOverlay(runtime.display)
            Gdx.graphics.requestRendering()
        } else {
            runtime.session.redrawCurrentScreen()
        }
    }

    private fun loadDictionary(): Set<String> =
        runCatching {
            val file = Gdx.files.internal("words.txt")
            if (file.exists()) {
                file.reader().useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.length in 2..7 }
                        .toSet()
                }
            } else {
                FALLBACK_WORDS
            }
        }.getOrDefault(FALLBACK_WORDS)

    private fun renderDrivingRestrictionOverlay(display: AsciiDisplay) {
        display.clear()
        val msg = "Vehicle In Motion: Gameplay Suspended"
        display.drawText(
            ((display.columns - msg.length) / 2).coerceAtLeast(1),
            display.rows / 2,
            "%c{yellow}$msg%c{}",
            display.columns - 2
        )
    }

    override fun dispose() {
        ctx?.let { runtime ->
            runtime.display.dispose()
            runtime.batch.dispose()
        }
        ctx = null
    }

    companion object {
        private val FALLBACK_WORDS: Set<String> = setOf("aa", "ab", "ad", "ae", "ba", "be", "bi", "bo", "by")
    }
}
