package com.spellcast.core.rot

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 1:1 port of ROT.RNG (Johannes Baagøe's Alea PRNG) supporting deterministic state snapshots.
 */
class AleaRng(seed: Long = System.currentTimeMillis()) {

    private data class State(
        val s0: Double,
        val s1: Double,
        val s2: Double,
        val c: Double
    )

    private var state: State = stateFromSeed(seed)

    fun setSeed(seed: Long) {
        state = stateFromSeed(seed)
    }

    fun getUniform(): Double =
        (2091639.0 * state.s0 + state.c * 2.3283064365386963e-10).let { t ->
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

    fun getState(): List<Double> = listOf(state.s0, state.s1, state.s2, state.c)

    fun setState(snapshot: List<Double>) {
        require(snapshot.size >= 4) { "Invalid Alea RNG state" }
        state = State(snapshot[0], snapshot[1], snapshot[2], snapshot[3])
    }

    companion object {
        private fun stateFromSeed(seed: Long): State =
            ((if (seed < 1L) 1.0 / (seed.toDouble() + 0.5) else seed.toDouble()) * 0.00000000023283064365386963 % 1.0)
                .let { s0 ->
                    ((s0 * 2246822519.0 + 0.3183098861837907) % 1.0).let { s1 ->
                        State(
                            s0 = s0,
                            s1 = s1,
                            s2 = (s1 * 3266489917.0 + 0.2718281828459045) % 1.0,
                            c = 1.0
                        )
                    }
                }
    }
}
