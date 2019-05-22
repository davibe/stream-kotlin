package it.dadeb.stream


val DEBUG = BuildConfig.DEBUG


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


open class Stream<T>(
    val replay: Boolean = false
) : Disposable {
    private var subscriptions = emptyList<Subscription<T>>()
    internal var disposables = emptyList<Disposable>()
    private var value: T? = null
    var valuePresent: Boolean = false
        private set

    // sub apis

    fun subscribe(handler: (T) -> Unit) : Subscription<T> {
        val sub = Subscription<T>(this, this, handler)
        sub.trackSubscription()
        subscriptions = subscriptions + sub
        replay(handler)
        return sub
    }

    fun unsubscribe(sub: Subscription<T>) {
        sub.trackUnsubscription()
        subscriptions = subscriptions.filter { it !== sub }
    }

    fun subscribe(target: Any, handler: (T?) -> Unit) : Subscription<T> {
        val sub = Subscription(target, this, handler)
        sub.trackSubscription()
        subscriptions = subscriptions + sub
        replay(handler)
        return sub
    }

    fun unsubscribe(target: Any) {
        subscriptions = subscriptions.filter {
            if (it.target === target) { it.trackUnsubscription() }
            it.target !== target
        }
    }

    // last value

    fun last(cb: (T) -> Unit) {
        if (!valuePresent) { return }
        cb(value as T)
    }

    private fun replay(handler: (T) -> Unit) {
        if (replay && valuePresent) {
            // value can be "safely" casted because the T is enforced by trigger()
            handler.invoke(value as T)
        }
    }

    // chainables

    fun trigger(value: T) : Stream<T> {
        this.value = value
        valuePresent = true
        subscriptions.forEach { it.handler(value) }
        return this
    }

    fun remember() : Stream<T> {
        if (this.replay) { return this }
        val event = Stream<T>(replay = true)
        event.value = this.value
        event.valuePresent = this.valuePresent
        event.disposables += this.subscribe { event.trigger(it) }
        this.disposables += event
        return event
    }

    fun <U> map(function: (T) -> U): Stream<U> {
        val event = Stream<U>(replay = true)
        if (this.valuePresent) { event.value = function(this.value as T) }
        event.valuePresent = this.valuePresent
        event.disposables += this.subscribe {
            event.trigger(function(it))
        }
        this.disposables += event
        return event
    }

    fun distinct(): Stream<T> {
        val event = Stream<T>(replay = true)
        event.value = this.value
        event.valuePresent = this.valuePresent

        val parent = this.remember()
        parent.disposables += event
        var sub: Subscription<T>? = null
        sub = parent.subscribe { initial ->
            sub?.dispose()
            var initialValue = initial
            event.trigger(initial)
            parent.subscribe { value ->
                if (value != initialValue) {
                    event.trigger(value)
                    initialValue = value
                }
            }
        }

        return event
    }

    fun <U> fold(initialValue: U, accumulator: ((U, T) -> U)): Stream<U> {
        var current = initialValue
        return this.map {
            val newValue = accumulator(current, it)
            current = newValue
            newValue
        }
    }

    override fun dispose() {
        (subscriptions + disposables).forEach { it.dispose() }
        subscriptions = emptyList()
        disposables = emptyList()
        valuePresent = false
        value = null
    }

}


class Subscription<T>(
    val target: Any?,
    val stream: Stream<T>,
    val handler: (T) -> Unit
) : Disposable {

    var debugSubscriber: String? = null

    override fun dispose() {
        stream.unsubscribe(this)
    }

    fun trackSubscription() {
        val depth = 4
        if (DEBUG) {
            val fullClassName = Thread.currentThread().stackTrace[depth].className
            val className = fullClassName.substring(fullClassName.lastIndexOf(".") + 1)
            val methodName = Thread.currentThread().stackTrace[depth].methodName
            val lineNumber = Thread.currentThread().stackTrace[depth].lineNumber
            val value = "$className.$methodName:$lineNumber"
            debugSubscriber = value
            AllocationTracker.plus(value)
        }
    }

    fun trackUnsubscription() {
        debugSubscriber?.let {
            AllocationTracker.minus(it)
            debugSubscriber = null
        }
    }
}


object AllocationTracker {

    var map = mutableMapOf<String, Int>()

    fun plus(key: String) {
        val value = map.get(key) ?: 0
        map.put(key, value + 1)
    }

    fun minus(key: String) {
        val value = map.get(key)
        if (value == null) {
            // not possible
            return
        }
        map.put(key, value - 1)
    }

    fun report(assert: Boolean = false) {
        var atleastone = false
        for ((key, value) in map) {
            if (value != 0) {
                print(key)
                atleastone = true
            }
        }
        if (atleastone) {
            print("leaking event handlers")
            if (assert) {
                error("leaking event handlers")
            }
        }
    }
}

data class Tuple2<A, B>(val a: A, val b: B)
data class Tuple3<A, B, C>(val a: A, val b: B, val c: C)
data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
data class Tuple6<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)

fun <A, B>combine(a: Stream<A>, b: Stream<B>) : Stream<Tuple2<A, B>> {
    val event = Stream<Tuple2<A, B>>()
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                event.trigger(Tuple2(va, vb))
            }
        }

    }
    a.subscribe { trigger() }
    b.subscribe { trigger() }
    // destroying
    var count = 2
    val dispose: () -> Unit = {
        count -= 1
        if (count == 0) {
            event.dispose()
        }
    }
    a.disposables += disposableFun { dispose() }
    b.disposables += disposableFun { dispose() }
    return event
}

fun <A, B, C>combine(a: Stream<A>, b: Stream<B>, c: Stream<C>) : Stream<Tuple3<A, B, C>> {
    val event = Stream<Tuple3<A, B, C>>()
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                c.last { vc ->
                    event.trigger(Tuple3(va, vb, vc))
                }
            }
        }

    }
    a.subscribe { trigger() }
    b.subscribe { trigger() }
    c.subscribe { trigger() }
    // destroying
    var count = 3
    val dispose: () -> Unit = {
        count -= 1
        if (count == 0) {
            event.dispose()
        }
    }
    a.disposables += disposableFun { dispose() }
    b.disposables += disposableFun { dispose() }
    c.disposables += disposableFun { dispose() }
    return event
}