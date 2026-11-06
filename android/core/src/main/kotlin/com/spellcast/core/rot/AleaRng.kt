package com.spellcast.core.rot

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 1:1 port of ROT.RNG (Johannes Baagøe's Alea PRNG) supporting deterministic state snapshots
 * and ROT's Array.random / Array.randomize helpers.
 */
class AleaRng(seed: Long = System.currentTimeMillis()) {

    private data class State(
        val s0: Double,
        val s1: Double,
        val s2: Double,
        val c: Double
    )

    private var state: State = stateFromSeed(seed)

    fun setSeed(seed: Long): AleaRng = apply {
        state = stateFromSeed(seed)
    }

    fun getUniform(): Double =
        (2091639.0 * state.s0 + state.c * FRAC).let { t ->
            floor(t).let { nextC ->
                State(
                    s0 = state.s1,
                    s1 = state.s2,
                    s2 = t - nextC,
                    c = nextC
                ).also { state = it }.s2
            }
        }

    fun getUniformInt(lowerBound: Int, upperBound: Int): Int =
        minOf(lowerBound, upperBound).let { min ->
            floor(getUniform() * (maxOf(lowerBound, upperBound) - min + 1)).toInt() + min
        }

    fun getNormal(mean: Double = 0.0, stddev: Double = 1.0): Double =
        generateSequence { (2.0 * getUniform() - 1.0) to (2.0 * getUniform() - 1.0) }
            .map { (u, v) -> u to (u * u + v * v) }
            .first { (_, r) -> r > 0.0 && r <= 1.0 }
            .let { (u, r) -> mean + u * sqrt(-2.0 * ln(r) / r) * stddev }

    fun getPercentage(): Int = 1 + floor(getUniform() * 100.0).toInt()

    fun <T> getWeightedValue(weights: Map<T, Number>): T =
        (getUniform() * weights.values.sumOf { it.toDouble() }).let { target ->
            weights.entries
                .runningFold(null as T? to 0.0) { (_, acc), (key, weight) ->
                    key to (acc + weight.toDouble())
                }
                .drop(1)
                .firstOrNull { (_, cumulative) -> target < cumulative }
                ?.first
                ?: weights.keys.last()
        }

    fun <T> randomElement(items: List<T>): T? =
        if (items.isEmpty()) null else items[floor(getUniform() * items.size).toInt()]

    fun <T> randomize(items: List<T>): List<T> =
        generateSequence(items to emptyList<T>()) { (remaining, acc) ->
            if (remaining.isEmpty()) {
                null
            } else {
                val idx = floor(getUniform() * remaining.size).toInt()
                (remaining.take(idx) + remaining.drop(idx + 1)) to (acc + remaining[idx])
            }
        }.last().second

    fun getState(): List<Double> = listOf(state.s0, state.s1, state.s2, state.c)

    fun setState(snapshot: List<Double>): AleaRng = apply {
        require(snapshot.size >= 4) { "Invalid Alea RNG state" }
        state = State(snapshot[0], snapshot[1], snapshot[2], snapshot[3])
    }

    fun clone(): AleaRng = AleaRng().setState(getState())

    companion object {
        private const val FRAC = 2.3283064365386963e-10 // 2^-32

        private fun uint32(value: Double): Long =
            value.toLong() and 0xFFFFFFFFL

        private fun stateFromSeed(seed: Long): State =
            (if (seed < 1L) 1.0 / seed.toDouble() else seed.toDouble()).let { norm ->
                uint32(norm).let { s0Bits ->
                    uint32(norm * 69069.0 + 1.0).let { s1Bits ->
                        uint32(s1Bits * 69069.0 + 1.0).let { s2Bits ->
                            State(
                                s0 = s0Bits * FRAC,
                                s1 = s1Bits * FRAC,
                                s2 = s2Bits * FRAC,
                                c = 1.0
                            )
                        }
                    }
                }
            }
    }
}
