package com.travisscholtens.spellcast

import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.Proxy

/**
 * Enforces AAOS driver safety compliance via Android's CarUxRestrictionsManager
 * when running on an Android Automotive OS vehicle head unit.
 */
object SafetyRestrictionManager {
    private const val CAR_UX_RESTRICTION_SERVICE = "uxrestriction"
    private const val UX_RESTRICTIONS_NO_INTERACTION = 0x20

    fun observe(
        context: Context,
        onRestrictionChanged: (isRestricted: Boolean) -> Unit
    ): () -> Unit {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            onRestrictionChanged(false)
            return {}
        }
        return runCatching {
            val car = Class.forName("android.car.Car")
                .getMethod("createCar", Context::class.java)
                .invoke(null, context)
            val manager = car.javaClass
                .getMethod("getCarManager", String::class.java)
                .invoke(car, CAR_UX_RESTRICTION_SERVICE)
            val listenerInterface = Class.forName(
                "android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener"
            )
            val getActiveRestrictions = Class.forName("android.car.drivingstate.CarUxRestrictions")
                .getMethod("getActiveRestrictions")

            fun isRestricted(restrictions: Any?): Boolean =
                ((restrictions?.let { getActiveRestrictions.invoke(it) } as? Int ?: 0) and
                    UX_RESTRICTIONS_NO_INTERACTION) != 0

            val listenerProxy = Proxy.newProxyInstance(
                listenerInterface.classLoader,
                arrayOf(listenerInterface)
            ) { _, method, args ->
                if (method.name == "onUxRestrictionsChanged" && !args.isNullOrEmpty()) {
                    onRestrictionChanged(isRestricted(args[0]))
                }
                null
            }

            manager.javaClass
                .getMethod("registerListener", listenerInterface)
                .invoke(manager, listenerProxy)

            onRestrictionChanged(
                isRestricted(
                    manager.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(manager)
                )
            )

            ({
                runCatching { manager.javaClass.getMethod("unregisterListener").invoke(manager) }
                runCatching { car.javaClass.getMethod("disconnect").invoke(car) }
                Unit
            })
        }.getOrElse {
            onRestrictionChanged(false)
            ({})
        }
    }
}
