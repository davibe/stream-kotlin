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


open class Stream<T>() : Disposable {
    private var subscriptions = emptyList<Subscription<T>>()
    internal var disposables = emptyList<Disposable>()
    private var value: T? = null
    var valuePresent: Boolean = false
        private set

    // sub apis

    fun subscribe(replay: Boolean = false, handler: (T) -> Unit) : Subscription<T> {
        val sub = Subscription<T>(this, this, handler)
        sub.trackSubscription()
        subscriptions = subscriptions + sub
        if (replay && valuePresent) {
            handler.invoke(this.value as T)
        }
        return sub
    }

    fun unsubscribe(sub: Subscription<T>) {
        sub.trackUnsubscription()
        subscriptions = subscriptions.filter { it !== sub }
    }

    fun subscribe(owner: Any, replay: Boolean = true, handler: (T) -> Unit) : Subscription<T> {
        val sub = Subscription(owner, this, handler)
        sub.trackSubscription()
        subscriptions = subscriptions + sub
        if (replay && valuePresent) {
            handler.invoke(this.value as T)
        }
        return sub
    }

    fun subscribe(owner: Any, handler: (T) -> Unit) : Subscription<T> {
        return subscribe(owner, false, handler)
    }


    fun unsubscribe(owner: Any) {
        subscriptions = subscriptions.filter {
            if (it.owner === owner) { it.trackUnsubscription() }
            it.owner !== owner
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
        subscriptions.forEach { it.handler(value) }
        return this
    }

    fun <U> map(function: (T) -> U): Stream<U> {
        val stream = Stream<U>()
        this.disposables += stream
        if (this.valuePresent) {
            stream.trigger(function(this.value as T))
        }

        this.subscribe {
            stream.trigger(function(it))
        }
        return stream
    }

    fun distinct() : Stream<T> {
        return distinct { it }
    }

    fun <U> distinct(f: (T) -> U): Stream<T> {
        val stream = Stream<T>()
        this.disposables += stream
        if (valuePresent) {
            stream.trigger(this.value as T)
        }

        var sub: Subscription<T>? = null
        sub = this.subscribe(replay = true) { initial ->
            sub?.dispose()
            var initialValue = initial
            stream.trigger(initial)
            this.subscribe { value ->
                if (f(value) != f(initialValue)) {
                    stream.trigger(value)
                    initialValue = value
                }
            }
        }

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
        this.disposables += stream
        if (valuePresent) { stream.trigger(this.value as T) }

        var sub: Subscription<T>? = null
        sub = this.subscribe(replay = true) { v ->
            if (f(v)) { stream.trigger(v) }
        }
        stream.disposables += sub

        return stream
    }

    fun take(amount: Int): Stream<T> {
        val stream = Stream<T>()
        this.disposables += stream
        if (valuePresent) { stream.trigger(this.value as T) }

        var count = 0
        var sub: Subscription<T>? = null
        sub = this.subscribe(replay = true) { v ->
            if (count <= amount) {
                stream.trigger(v)
                count += 1
            } else {
                sub?.dispose()
            }
        }
        stream.disposables += sub

        return stream
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
    val owner: Any?,
    val stream: Stream<T>,
    val handler: (T) -> Unit
) : Disposable {

    var debugSubscriber: String? = null

    override fun dispose() {
        stream.unsubscribe(this)
    }

    fun trackSubscription() {
        if (!DEBUG) { return }
        val stackTrack = Thread.currentThread().stackTrace
        val value = (2 until Math.min(stackTrack.size, 10)).map {
            val item = stackTrack[it]
            val fullClassName = item.className
            val className = fullClassName.substring(fullClassName.lastIndexOf(".") + 1)
            val methodName = item.methodName
            val lineNumber = item.lineNumber
            val value = "$className.$methodName:$lineNumber"
            value
        }.joinToString(separator = "\n  ")
        debugSubscriber = value
        AllocationTracker.plus(value)
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
            print("leaking stream handlers")
            if (assert) {
                error("leaking stream handlers")
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
    val stream = Stream<Tuple2<A, B>>()
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                stream.trigger(Tuple2(va, vb))
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
            stream.dispose()
        }
    }
    a.disposables += disposableFun { dispose() }
    b.disposables += disposableFun { dispose() }
    return stream
}

fun <A, B, C>combine(a: Stream<A>, b: Stream<B>, c: Stream<C>) : Stream<Tuple3<A, B, C>> {
    val stream = Stream<Tuple3<A, B, C>>()
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                c.last { vc ->
                    stream.trigger(Tuple3(va, vb, vc))
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
            stream.dispose()
        }
    }
    a.disposables += disposableFun { dispose() }
    b.disposables += disposableFun { dispose() }
    c.disposables += disposableFun { dispose() }
    return stream
}

fun <A, B, C, D>combine(a: Stream<A>, b: Stream<B>, c: Stream<C>, d: Stream<D>) : Stream<Tuple4<A, B, C, D>> {
    val stream = Stream<Tuple4<A, B, C, D>>()
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                c.last { vc ->
                    d.last { vd ->
                        stream.trigger(Tuple4(va, vb, vc, vd))
                    }
                }
            }
        }

    }
    a.subscribe { trigger() }
    b.subscribe { trigger() }
    c.subscribe { trigger() }
    d.subscribe { trigger() }
    // destroying
    var count = 4
    val dispose: () -> Unit = {
        count -= 1
        if (count == 0) {
            stream.dispose()
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
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                c.last { vc ->
                    d.last { vd ->
                        e.last { ve ->
                            stream.trigger(Tuple5(va, vb, vc, vd, ve))
                        }
                    }
                }
            }
        }

    }
    a.subscribe { trigger() }
    b.subscribe { trigger() }
    c.subscribe { trigger() }
    d.subscribe { trigger() }
    e.subscribe { trigger() }
    // destroying
    var count = 5
    val dispose: () -> Unit = {
        count -= 1
        if (count == 0) {
            stream.dispose()
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
    val trigger: () -> Unit = {
        a.last { va ->
            b.last { vb ->
                c.last { vc ->
                    d.last { vd ->
                        e.last { ve ->
                            f.last { vf ->
                                stream.trigger(Tuple6(va, vb, vc, vd, ve, vf))
                            }
                        }
                    }
                }
            }
        }

    }
    a.subscribe { trigger() }
    b.subscribe { trigger() }
    c.subscribe { trigger() }
    d.subscribe { trigger() }
    e.subscribe { trigger() }
    f.subscribe { trigger() }
    // destroying
    var count = 6
    val dispose: () -> Unit = {
        count -= 1
        if (count == 0) {
            stream.dispose()
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