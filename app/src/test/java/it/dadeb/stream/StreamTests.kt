package it.dadeb.stream


import org.junit.After
import org.junit.Assert
import org.junit.Test

class StreamTests {

    @After
    fun testCheckAllocations() {
        System.gc()
        Thread.sleep(100)
        System.gc()
        Thread.sleep(100)

        AllocationTracker.report(true)
    }

    @Test
    fun testSubscribeByTarget() {
        val stream = Stream<String?>()
        var result: String? = null
        stream.subscribe(this) { string -> result = string }
        stream.trigger("ciao")
        stream.trigger("mondo")
        Assert.assertEquals(result, "mondo")
        stream.dispose()
    }

    @Test
    fun testUnsubscribeByTarget() {
        val stream = Stream<String?>()
        var result: String? = null
        stream.subscribe(this) { string -> result = string }
        stream.trigger("ciao")
        stream.unsubscribe(this)
        stream.trigger("mondo")
        Assert.assertEquals(result, "ciao")
        stream.dispose()
    }

    @Test
    fun testSubscribeSimple() {
        val stream = Stream<String?>()
        var result: String? = null
        stream.subscribe() { string -> result = string }
        stream.trigger("ciao")
        stream.trigger("mondo")
        Assert.assertEquals(result, "mondo")
        stream.dispose()
    }

    @Test
    fun testUnsubscribeSimple() {
        val stream = Stream<String?>()
        var result: String? = null
        val subscription = stream.subscribe() { string -> result = string }
        stream.trigger("ciao")
        stream.unsubscribe(subscription)
        stream.trigger("mondo")
        Assert.assertEquals("ciao", result)
        stream.dispose()
    }

    @Test
    fun testNoValue() {
        val stream = Stream<Unit?>()
        var called = false
        stream.subscribe { called = true }
        stream.trigger(null)
        Assert.assertEquals(called, true)
        stream.dispose()
    }

    @Test
    fun testLast() {
        val stream = Stream<String>()
        Assert.assertEquals(false, stream.valuePresent)
        stream.last { Assert.assertEquals(null, it) }
        stream.trigger("1")
        Assert.assertEquals(true, stream.valuePresent)
        stream.last { Assert.assertEquals("1", it) }
    }

    @Test
    fun testLastOptional() {
        val stream = Stream<String?>()
        Assert.assertEquals(false, stream.valuePresent)
        stream.last { Assert.assertEquals(null, it) }
        stream.trigger(null)
        Assert.assertEquals(true, stream.valuePresent)
        stream.last { Assert.assertEquals(null, it) }
        stream.trigger("1")
        Assert.assertEquals(true, stream.valuePresent)
        stream.last { Assert.assertEquals("1", it) }
    }


    @Test
    fun testMap() {
        val stream = Stream<Int?>()
        stream.trigger(null)
        var result = emptyList<String>()
        val sub = stream.map { "${it}" }.subscribe(replay = true) { result += it }
        stream.trigger(1).trigger(2)
        sub.dispose()
        Assert.assertEquals(listOf("null", "1", "2"), result)
    }

    @Test
    fun testMapMemoryLeak() {
        var stream: Stream<Int?>?
        stream = Stream<Int?>()
        stream.trigger(null)
        var result = emptyList<String>()
        val scope: () -> Unit = { // used to ensure sub goes out of scope
            val sub = stream!!
                .map { "${it}" }
                .map { "${it}" }
                .map { "${it}" }
                .map { "${it}" }
                .subscribe(replay = true) { result += it }
            stream!!.trigger(1).trigger(2)
            sub.dispose()
        }
        scope()
        stream = null
        System.gc()
        Thread.sleep(10)
        AllocationTracker.report(true)
    }


    @Test
    fun testDistinct() {
        val stream = Stream<String>()
        var result = emptyList<String>()
        val sub = stream.distinct().subscribe(replay = true) { result += it }
        stream
            .trigger("1")
            .trigger("2").trigger("2")
            .trigger("3").trigger("3").trigger("3")
        Assert.assertEquals(listOf("1", "2", "3"), result)
        sub.dispose()
    }

    @Test
    fun testDistinctNull() {
        val stream = Stream<String?>()
        var result = emptyList<String?>()
        val sub = stream.distinct().subscribe(replay = true) { result += it }
        stream
            .trigger(null).trigger(null)
            .trigger("1")
            .trigger("2").trigger("2")
            .trigger(null)
            .trigger("3").trigger("3").trigger("3")
            .trigger(null).trigger(null)
        Assert.assertEquals(listOf(null, "1", "2", null, "3", null), result)
        sub.dispose()
    }

    @Test
    fun testFold() {
        val stream1 = Stream<String?>()
        var result = Pair<String?, String?>("", null)
        val sub = stream1
            .trigger(null)
            .fold(Pair<String?, String?>(null, null)) { (_, old), new ->  Pair(old, new) }
            .subscribe(replay = true) { pair -> result = pair }

        Assert.assertEquals(Pair(null, null), result)
        stream1.trigger("1")
        Assert.assertEquals(Pair(null, "1"), result)
        stream1.trigger("2")
        Assert.assertEquals(Pair("1", "2"), result)
        stream1.trigger("3")
        Assert.assertEquals(Pair("2", "3"), result)
        stream1.trigger(null)
        Assert.assertEquals(Pair("3", null), result)
        sub.dispose()
    }

    @Test
    fun testFilter() {
        val stream = Stream<String>()
        var result = emptyList<String>()
        stream.trigger("2").trigger("2")
        val sub = stream.filter { it == "2" }.subscribe(replay = false) { result += it }
        stream
            .trigger("1")
            .trigger("2").trigger("2")
            .trigger("3").trigger("3").trigger("3")
        Assert.assertEquals(listOf("2", "2"), result)
        sub.dispose()
    }

    @Test
    fun testTake() {
        val stream = Stream<String>()
        var result = emptyList<String>()
        stream.trigger("2").trigger("2")
        val sub = stream.take(3).subscribe(replay = false) { result += it }
        stream
            .trigger("1")
            .trigger("2").trigger("2")
            .trigger("3").trigger("3").trigger("3")
        Assert.assertEquals(listOf("1", "2", "2"), result)
        sub.dispose()
    }

    @Test
    fun testTake2() {
        val stream = Stream<String>()
        var result = emptyList<String>()
        stream.trigger("2").trigger("2")
        val sub = stream.take(3).subscribe(replay = true) { result += it }
        stream
            .trigger("1")
            .trigger("2").trigger("2")
            .trigger("3").trigger("3").trigger("3")
        Assert.assertEquals(listOf("2", "1", "2", "2"), result)
        sub.dispose()
    }

    @Test
    fun testTakeMany() {
        val stream = Stream<String>()
        var result = emptyList<String>()
        stream.trigger("2").trigger("2")
        val sub = stream.take(300).subscribe(replay = true) { result += it }
        stream
            .trigger("1")
            .trigger("2").trigger("2")
            .trigger("3").trigger("3").trigger("3")
        Assert.assertEquals(listOf("2", "1", "2", "2", "3", "3", "3"), result)
        sub.dispose()
    }

    @Test
    fun testCombine2() {
        val a = Stream<String>()
        val b = Stream<String?>()
        var result = emptyList<Tuple2<String, String?>>()
        val sub = combine(a, b).distinct().subscribe { result += it }
        a.trigger("1")
        b.trigger(null)
        b.trigger("2")
        a.trigger("2")
        a.trigger("2")
        a.dispose()
        b.trigger("3")
        Assert.assertEquals(
            listOf(
                Tuple2("1", null),
                Tuple2("1", "2"),
                Tuple2("2", "2")
            ),
            result
        )
        sub.dispose()
    }

    @Test
    fun testSubscriptionRetainsStream() {
        val a = Weak(Stream<String>())
        assert(a.get() != null)
        System.gc()
        Thread.sleep(10)
        assert(a.get() == null) { "stream is leaking" }

        val b = Weak(Stream<String>())
        val sub = Weak(b.get()?.subscribe { it })
        assert(b.get() != null)
        System.gc()
        Thread.sleep(10)
        assert(b.get() != null) { "stream not retained" }

        sub.get()?.dispose()
        System.gc()
        Thread.sleep(10)
        assert(b.get() == null) { "stream is leaking" }
    }
}