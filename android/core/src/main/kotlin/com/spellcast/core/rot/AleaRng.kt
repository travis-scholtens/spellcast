package com.spellcast.core.rot

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 1:1 port of ROT.RNG (Johannes Baagøe's Alea PRNG) supporting deterministic state snapshots.
 */
class AleaRng(seed: Long = System.currentTimeMillis()) {
    private var s0: Double = 0.0
    private var s1: Double = 0.0
    private var s2: Double = 0.0
    private var c: Double = 1.0

    init {
        setSeed(seed)
    }

    fun setSeed(seed: Long) {
        val normSeed = (if (seed < 1L) 1.0 / (seed.toDouble() + 0.5) else seed.toDouble())
        s0 = (normSeed * 0.00000000023283064365386963) % 1.0
        s1 = (s0 * 2246822519.0 + 0.3183098861837907) % 1.0
        s2 = (s1 * 3266489917.0 + 0.2718281828459045) % 1.0
        c = 1.0
    }

    fun getUniform(): Double {
        val t = 2091639.0 * s0 + c * 2.3283064365386963e-10
        s0 = s1
        s1 = s2
        c = floor(t)
        s2 = t - c
        return s2
    }

    fun getUniformInt(lowerBound: Int, upperBound: Int): Int {
        val max = maxOf(lowerBound, upperBound)
        val min = minOf(lowerBound, upperBound)
        return floor(getUniform() * (max - min + 1)).toInt() + min
    }

    fun getNormal(mean: Double = 0.0, stddev: Double = 1.0): Double {
        var u: Double
        var v: Double
        var r: Double
        do {
            u = 2.0 * getUniform() - 1.0
            v = 2.0 * getUniform() - 1.0
            r = u * u + v * v
        } while (r > 1.0 || r == 0.0)
        val gauss = u * sqrt(-2.0 * ln(r) / r)
        return mean + gauss * stddev
    }

    fun <T> getWeightedValue(weights: Map<T, Number>): T {
        val total = weights.values.sumOf { it.toDouble() }
        val random = getUniform() * total
        var part = 0.0
        for ((key, weight) in weights) {
            part += weight.toDouble()
            if (random < part) {
                return key
            }
        }
        return weights.keys.last()
    }

    fun getState(): List<Double> = listOf(s0, s1, s2, c)

    fun setState(state: List<Double>) {
        require(state.size >= 4) { "Invalid Alea RNG state" }
        s0 = state[0]
        s1 = state[1]
        s2 = state[2]
        c = state[3]
    }
}
