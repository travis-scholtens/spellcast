package com.spellcast.core.rot

/**
 * 1:1 port of ROT.EventQueue, ROT.Scheduler.Speed, and ROT.Engine in functional style.
 */
class EventQueue<T> {

    private data class ScheduledEvent<T>(val event: T, val time: Double)

    private data class QueueState<T>(
        val time: Double = 0.0,
        val events: List<ScheduledEvent<T>> = emptyList()
    )

    private var state: QueueState<T> = QueueState()

    fun getTime(): Double = state.time

    fun clear(): EventQueue<T> = apply {
        state = state.copy(events = emptyList())
    }

    fun add(event: T, time: Double) {
        val entry = ScheduledEvent(event, time)
        val insertIdx = state.events.indexOfFirst { it.time > time }
        state = state.copy(
            events = if (insertIdx == -1) {
                state.events + entry
            } else {
                state.events.take(insertIdx) + entry + state.events.drop(insertIdx)
            }
        )
    }

    fun get(): T? {
        val head = state.events.firstOrNull() ?: return null
        val tail = state.events.drop(1)
        state = if (head.time > 0.0) {
            QueueState(
                time = state.time + head.time,
                events = tail.map { it.copy(time = it.time - head.time) }
            )
        } else {
            state.copy(events = tail)
        }
        return head.event
    }

    fun getEventTime(event: T): Double? =
        state.events.firstOrNull { it.event == event }?.time

    fun remove(event: T): Boolean =
        state.events.indexOfFirst { it.event == event }.let { idx ->
            if (idx == -1) {
                false
            } else {
                state = state.copy(
                    events = state.events.take(idx) + state.events.drop(idx + 1)
                )
                true
            }
        }
}

fun interface Thenable {
    fun then(callback: () -> Unit)
}

interface SpeedActor {
    fun getSpeed(): Int
    fun act(): Thenable?
}

class SpeedScheduler<T : SpeedActor> {

    private data class SchedulerState<T>(
        val repeat: List<T> = emptyList(),
        val current: T? = null
    )

    private val queue = EventQueue<T>()
    private var state: SchedulerState<T> = SchedulerState()

    fun getTime(): Double = queue.getTime()

    fun add(item: T, repeat: Boolean, time: Double? = null): SpeedScheduler<T> = apply {
        queue.add(item, time ?: (1.0 / item.getSpeed()))
        if (repeat) {
            state = state.copy(repeat = state.repeat + item)
        }
    }

    fun getTimeOf(item: T): Double? = queue.getEventTime(item)

    fun clear(): SpeedScheduler<T> = apply {
        queue.clear()
        state = SchedulerState()
    }

    fun remove(item: T): Boolean {
        val removedFromQueue = queue.remove(item)
        state = SchedulerState(
            repeat = state.repeat.filterNot { it == item },
            current = state.current.takeUnless { it == item }
        )
        return removedFromQueue
    }

    fun next(): T? {
        state.current?.takeIf { it in state.repeat }?.let { active ->
            queue.add(active, 1.0 / active.getSpeed())
        }
        return queue.get().also { nextActor ->
            state = state.copy(current = nextActor)
        }
    }
}

class RotEngine<T : SpeedActor>(private val scheduler: SpeedScheduler<T>) {

    private var lockCount: Int = 1

    fun start(): RotEngine<T> = unlock()

    fun lock(): RotEngine<T> = apply {
        lockCount++
    }

    fun unlock(): RotEngine<T> = apply {
        check(lockCount > 0) { "Cannot unlock unlocked engine" }
        lockCount--
        stepLoop()
    }

    private tailrec fun stepLoop() {
        if (lockCount != 0) return
        val actor = scheduler.next()
        if (actor == null) {
            lock()
            return
        }
        val thenable = actor.act()
        if (thenable != null) {
            lock()
            thenable.then { unlock() }
            return
        }
        stepLoop()
    }
}
