package com.spellcast.automotive

import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.Proxy

/**
 * Enforces AAOS driver safety compliance via Android's CarUxRestrictionsManager
 * when running on an Android Automotive OS vehicle head unit.
 */
class SafetyRestrictionManager(
    private val context: Context,
    private val onRestrictionChanged: (isRestricted: Boolean) -> Unit
) {
    private var car: Any? = null
    private var uxRestrictionsManager: Any? = null

    companion object {
        private const val CAR_UX_RESTRICTION_SERVICE = "uxrestriction"
        private const val UX_RESTRICTIONS_NO_INTERACTION = 0x20 // CarUxRestrictions.UX_RESTRICTIONS_NO_INTERACTION
    }

    fun init() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            onRestrictionChanged(false)
            return
        }
        try {
            val carClass = Class.forName("android.car.Car")
            val createCar = carClass.getMethod("createCar", Context::class.java)
            car = createCar.invoke(null, context)

            val getCarManager = carClass.getMethod("getCarManager", String::class.java)
            uxRestrictionsManager = getCarManager.invoke(car, CAR_UX_RESTRICTION_SERVICE)

            val mgrClass = Class.forName("android.car.drivingstate.CarUxRestrictionsManager")
            val listenerInterface = Class.forName(
                "android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener"
            )
            val restrictionsClass = Class.forName("android.car.drivingstate.CarUxRestrictions")
            val getActiveRestrictions = restrictionsClass.getMethod("getActiveRestrictions")

            val listenerProxy = Proxy.newProxyInstance(
                listenerInterface.classLoader,
                arrayOf(listenerInterface)
            ) { _, method, args ->
                if (method.name == "onUxRestrictionsChanged" && !args.isNullOrEmpty() && args[0] != null) {
                    val active = getActiveRestrictions.invoke(args[0]) as? Int ?: 0
                    val requiresLockout = (active and UX_RESTRICTIONS_NO_INTERACTION) != 0
                    onRestrictionChanged(requiresLockout)
                }
                null
            }

            val registerListener = mgrClass.getMethod("registerListener", listenerInterface)
            registerListener.invoke(uxRestrictionsManager, listenerProxy)

            val getCurrentRestrictions = mgrClass.getMethod("getCurrentCarUxRestrictions")
            val current = getCurrentRestrictions.invoke(uxRestrictionsManager)
            if (current != null) {
                val active = getActiveRestrictions.invoke(current) as? Int ?: 0
                onRestrictionChanged((active and UX_RESTRICTIONS_NO_INTERACTION) != 0)
            } else {
                onRestrictionChanged(false)
            }
        } catch (_: Throwable) {
            onRestrictionChanged(false)
        }
    }

    fun dispose() {
        try {
            uxRestrictionsManager?.let { mgr ->
                mgr.javaClass.getMethod("unregisterListener").invoke(mgr)
            }
            car?.let { c ->
                c.javaClass.getMethod("disconnect").invoke(c)
            }
        } catch (_: Throwable) {
        } finally {
            uxRestrictionsManager = null
            car = null
        }
    }
}
