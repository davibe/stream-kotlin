package it.dadeb.stream

import java.lang.ref.WeakReference


interface Disposable {
    fun dispose()
}
inline fun disposableFun(crossinline function: () -> Unit) : Disposable {
    return object : Disposable {
        override fun dispose() {
            function()
        }
    }
}

internal class Weak<T>(value: T) {
    private var value: WeakReference<T> = WeakReference(value)
    fun get(): T? = value.get()
}

open class Stream<T>() : Disposable {
    private var subscriptions = emptyList<Weak<Subscription<T>>>()
    internal var disposables = emptyList<Disposable>()
    private var value: T? = null
    var valuePresent: Boolean = false
        private set

    var debugKey: String? = null

    init {
        AllocationTracker.plus(this)
    }

    // sub apis

    fun subscribe(replay: Boolean = false, strong: Boolean = true, handler: (T) -> Unit) : Subscription<T> {
        val sub = Subscription(this, this, strong, handler)
        if (sub.strong) { Subscription.registry += sub }
        AllocationTracker.plus(sub)
        subscriptions = subscriptions + Weak(sub)

        if (replay && valuePresent) {
            handler.invoke(this.value as T)
        }
        return sub
    }

    fun unsubscribe(sub: Subscription<T>) {
        AllocationTracker.minus(sub)
        if (sub.strong) { Subscription.registry -= sub }
        subscriptions = subscriptions.filter { it.get() !== sub }
    }

    // sub apis based on ownership

    fun subscribe(owner: Any, replay: Boolean = true, handler: (T) -> Unit) : Subscription<T> {
        val sub = Subscription(owner, this, true, handler)
        if (sub.strong) { Subscription.registry += sub }
        AllocationTracker.plus(sub)
        subscriptions = subscriptions + Weak(sub)
        if (replay && valuePresent) {
            handler.invoke(this.value as T)
        }
        return sub
    }

    fun subscribe(owner: Any, handler: (T) -> Unit) : Subscription<T> = subscribe(owner, false, handler)

    fun unsubscribe(owner: Any) {
        subscriptions = subscriptions.filter {
            val sub = it.get() ?: return@filter true
            if (sub.owner === owner) {
                AllocationTracker.minus(sub)
                if (sub.strong) { Subscription.registry -= sub }
            }
            sub.owner !== owner
        }
    }

    // last value

    fun last(cb: (T) -> Unit) {
        if (!valuePresent) { return }
        cb(value as T)
    }

    // chainables

    fun trigger(value: T) : Stream<T> {
        this.value = value
        valuePresent = true
        subscriptions.forEach { it.get()?.handler?.invoke(value) }
        return this
    }

    fun <U> map(function: (T) -> U): Stream<U> {
        val stream = Stream<U>()
        if (this.valuePresent) {
            stream.trigger(function(this.value as T))
        }

        val streamWeak = Weak(stream)
        stream.disposables += this.subscribe(strong = false) {
            streamWeak.get()?.trigger(function(it))
        }

        return stream
    }

    fun distinct() : Stream<T> {
        return distinct { it }
    }

    fun <U> distinct(f: (T) -> U): Stream<T> {
        val stream = Stream<T>()
        if (valuePresent) {
            stream.trigger(this.value as T)
        }

        val streamWeak = Weak(stream)
        var sub: Subscription<T>? = null
        sub = this.subscribe(replay = true, strong = false) { initial ->
            sub?.dispose()
            var initialValue = initial
            val stream = streamWeak.get() ?: return@subscribe
            stream.trigger(initial)
            stream.disposables += this.subscribe(strong = false) { value ->
                if (f(value) != f(initialValue)) {
                    streamWeak.get()?.trigger(value)
                    initialValue = value
                }
            }
        }
        stream.disposables += sub

        return stream
    }

    fun <U> fold(initialValue: U, accumulator: ((U, T) -> U)): Stream<U> {
        var current = initialValue
        return this.map {
            val newValue = accumulator(current, it)
            current = newValue
            newValue
        }
    }

    fun filter(f: (T) -> Boolean): Stream<T> {
        val stream = Stream<T>()
        if (valuePresent) { stream.trigger(this.value as T) }

        val streamWeak = Weak(stream)
        stream.disposables += this.subscribe(replay = true, strong = false) { v ->
            if (f(v)) { streamWeak.get()?.trigger(v) }
        }

        return stream
    }

    fun take(amount: Int): Stream<T> {
        val stream = Stream<T>()
        if (valuePresent) { stream.trigger(this.value as T) }

        var count = 0
        var sub: Subscription<T>? = null
        val streamWeak = Weak(stream)
        sub = this.subscribe(replay = true, strong = false) { v ->
            if (count <= amount) {
                streamWeak.get()?.trigger(v)
                count += 1
            } else {
                sub?.dispose()
            }
        }
        stream.disposables += sub

        return stream
    }

    override fun dispose() {
        AllocationTracker.minus(this)
        subscriptions.forEach { it.get()?.dispose() }
        disposables.forEach { it.dispose() }
        subscriptions = emptyList()
        disposables = emptyList()
        valuePresent = false
        value = null
    }

    protected fun finalize() {
        dispose()
    }

}


class Subscription<T>(
    val owner: Any?,
    var stream: Stream<T>,
    val strong: Boolean,
    val handler: (T) -> Unit
) : Disposable {

    var debugKey: String? = null

    override fun dispose() {
        stream.unsubscribe(this)
    }

    protected fun finalize() {
        dispose()
    }

    companion object {
        var registry = emptyList<Any>()
    }

}



object AllocationTracker {

    val DEBUG = BuildConfig.DEBUG

    var map = mutableMapOf<String, Int>()

    fun plus(key: String) {
        val value = map.get(key) ?: 0
        map.put(key, value + 1)
    }

    fun minus(key: String) {
        val value = map.get(key) ?: return
        map.put(key, value - 1)
    }

    inline fun generate() : String {
        val stackTrack = Thread.currentThread().stackTrace
        val value = (1 until Math.min(stackTrack.size, 10)).map {
            val item = stackTrack[it]
            val fullClassName = item.className
            val className = fullClassName.substring(fullClassName.lastIndexOf(".") + 1)
            val methodName = item.methodName
            val lineNumber = item.lineNumber
            val value = "$className.$methodName:$lineNumber"
            value
        }.joinToString(separator = "\n  ")
        return value
    }

    fun plus(sub: Subscription<*>) {
        if (!DEBUG) { return }
        var key = "Subscription ${generate()}"
        sub.debugKey = key
        plus(key)
    }

    fun minus(sub: Subscription<*>) {
        if (!DEBUG) { return }
        sub.debugKey?.let {
            minus(it)
            sub.debugKey = null
        }
    }

    fun plus(stream: Stream<*>) {
        if (!DEBUG) { return }
        var key = "Stream ${generate()}"
        stream.debugKey = key
        plus(key)
    }

    fun minus(stream: Stream<*>) {
        if (!DEBUG) { return }
        stream.debugKey?.let {
            minus(it)
            stream.debugKey = null
        }
    }


    fun report(assert: Boolean = false) : Int {
        var atleastone = false
        var count = 0
        for ((key, value) in map) {
            if (value != 0) {
                count += 1
                print(key)
                atleastone = true
            }
        }
        if (atleastone) {
            print("leaking stream handlers")
            if (assert) {
                error("leaking stream handlers")
            }
        }
        return count
    }
}

data class Tuple2<A, B>(val a: A, val b: B)
data class Tuple3<A, B, C>(val a: A, val b: B, val c: C)
data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
data class Tuple6<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)

fun <A, B>combine(a: Stream<A>, b: Stream<B>) : Stream<Tuple2<A, B>> {
    val stream = Stream<Tuple2<A, B>>()
    val streamWeak = Weak(stream)
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                streamWeak.get()?.trigger(Tuple2(va, vb))
            }
        }
    }
    stream.disposables += a.subscribe(strong = false) { trigger() }
    stream.disposables += b.subscribe(strong = false) { trigger() }
    // destroying when all parents die
    var count = 2
    val dispose: () -> Unit = {
        count -= 1
        if (count == 0) {
            streamWeak.get()?.dispose()
        }
    }
    a.disposables += disposableFun { dispose() }
    b.disposables += disposableFun { dispose() }
    return stream
}

fun <A, B, C>combine(a: Stream<A>, b: Stream<B>, c: Stream<C>) : Stream<Tuple3<A, B, C>> {
    val stream = Stream<Tuple3<A, B, C>>()
    val streamWeak = Weak(stream)
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                c.last { vc ->
                    streamWeak.get()?.trigger(Tuple3(va, vb, vc))
                }
            }
        }

    }
    stream.disposables += a.subscribe(strong = false) { trigger() }
    stream.disposables += b.subscribe(strong = false) { trigger() }
    stream.disposables += c.subscribe(strong = false) { trigger() }
    // destroying
    var count = 3
    val dispose: () -> Unit = {
        count -= 1
        if (count == 0) {
            streamWeak.get()?.dispose()
        }
    }
    a.disposables += disposableFun { dispose() }
    b.disposables += disposableFun { dispose() }
    c.disposables += disposableFun { dispose() }
    return stream
}

fun <A, B, C, D>combine(a: Stream<A>, b: Stream<B>, c: Stream<C>, d: Stream<D>) : Stream<Tuple4<A, B, C, D>> {
    val stream = Stream<Tuple4<A, B, C, D>>()
    val streamWeak = Weak(stream)
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                c.last { vc ->
                    d.last { vd ->
                        streamWeak.get()?.trigger(Tuple4(va, vb, vc, vd))
                    }
                }
            }
        }

    }
    stream.disposables += a.subscribe(strong = false) { trigger() }
    stream.disposables += b.subscribe(strong = false) { trigger() }
    stream.disposables += c.subscribe(strong = false) { trigger() }
    stream.disposables += d.subscribe(strong = false) { trigger() }
    // destroying
    var count = 4
    val dispose: () -> Unit = {
        count -= 1
        if (count == 0) {
            streamWeak.get()?.dispose()
        }
    }
    a.disposables += disposableFun { dispose() }
    b.disposables += disposableFun { dispose() }
    c.disposables += disposableFun { dispose() }
    d.disposables += disposableFun { dispose() }
    return stream
}

fun <A, B, C, D, E>combine(a: Stream<A>, b: Stream<B>, c: Stream<C>, d: Stream<D>, e: Stream<E>) : Stream<Tuple5<A, B, C, D, E>> {
    val stream = Stream<Tuple5<A, B, C, D, E>>()
    val streamWeak = Weak(stream)
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                c.last { vc ->
                    d.last { vd ->
                        e.last { ve ->
                            streamWeak.get()?.trigger(Tuple5(va, vb, vc, vd, ve))
                        }
                    }
                }
            }
        }

    }
    stream.disposables += a.subscribe(strong = false) { trigger() }
    stream.disposables += b.subscribe(strong = false) { trigger() }
    stream.disposables += c.subscribe(strong = false) { trigger() }
    stream.disposables += d.subscribe(strong = false) { trigger() }
    stream.disposables += e.subscribe(strong = false) { trigger() }
    // destroying
    var count = 5
    val dispose: () -> Unit = {
        count -= 1
        if (count == 0) {
            streamWeak.get()?.dispose()
        }
    }
    a.disposables += disposableFun { dispose() }
    b.disposables += disposableFun { dispose() }
    c.disposables += disposableFun { dispose() }
    d.disposables += disposableFun { dispose() }
    e.disposables += disposableFun { dispose() }
    return stream
}


fun <A, B, C, D, E, F>combine(a: Stream<A>, b: Stream<B>, c: Stream<C>, d: Stream<D>, e: Stream<E>, f: Stream<F>) : Stream<Tuple6<A, B, C, D, E, F>> {
    val stream = Stream<Tuple6<A, B, C, D, E, F>>()
    val streamWeak = Weak(stream)
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                c.last { vc ->
                    d.last { vd ->
                        e.last { ve ->
                            f.last { vf ->
                                streamWeak.get()?.trigger(Tuple6(va, vb, vc, vd, ve, vf))
                            }
                        }
                    }
                }
            }
        }
    }
    stream.disposables += a.subscribe(strong = false) { trigger() }
    stream.disposables += b.subscribe(strong = false) { trigger() }
    stream.disposables += c.subscribe(strong = false) { trigger() }
    stream.disposables += d.subscribe(strong = false) { trigger() }
    stream.disposables += e.subscribe(strong = false) { trigger() }
    stream.disposables += f.subscribe(strong = false) { trigger() }
    // destroying
    var count = 6
    val dispose: () -> Unit = {
        count -= 1
        if (count == 0) {
            streamWeak.get()?.dispose()
        }
    }
    a.disposables += disposableFun { dispose() }
    b.disposables += disposableFun { dispose() }
    c.disposables += disposableFun { dispose() }
    d.disposables += disposableFun { dispose() }
    e.disposables += disposableFun { dispose() }
    f.disposables += disposableFun { dispose() }
    return stream
}