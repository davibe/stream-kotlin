package it.dadeb.stream


import org.junit.Assert
import org.junit.Test

class StreamTests {

    @Test
    fun testSubscribeByTarget() {
        val stringChange = Stream<String?>()
        var result: String? = null
        stringChange.subscribe(this) { string -> result = string }
        stringChange.trigger("ciao")
        stringChange.trigger("mondo")
        Assert.assertEquals(result, "mondo")
        stringChange.dispose()
    }

    @Test
    fun testUnsubscribeByTarget() {
        val stringChange = Stream<String?>()
        var result: String? = null
        stringChange.subscribe(this) { string -> result = string }
        stringChange.trigger("ciao")
        stringChange.unsubscribe(this)
        stringChange.trigger("mondo")
        Assert.assertEquals(result, "ciao")
        stringChange.dispose()
    }

    @Test
    fun testSubscribeSimple() {
        val stringChange = Stream<String?>()
        var result: String? = null
        stringChange.subscribe() { string -> result = string }
        stringChange.trigger("ciao")
        stringChange.trigger("mondo")
        Assert.assertEquals(result, "mondo")
        stringChange.dispose()
    }

    @Test
    fun testUnsubscribeSimple() {
        val stringChange = Stream<String?>()
        var result: String? = null
        val subscription = stringChange.subscribe() { string -> result = string }
        stringChange.trigger("ciao")
        stringChange.unsubscribe(subscription)
        stringChange.trigger("mondo")
        Assert.assertEquals(result, "ciao")
        stringChange.dispose()
    }

    @Test
    fun testNoValue() {
        val stringChange = Stream<Unit?>()
        var called = false
        stringChange.subscribe { called = true }
        stringChange.trigger(null)
        Assert.assertEquals(called, true)
        stringChange.dispose()
    }

    @Test
    fun testLast() {
        val ev = Stream<String>()
        Assert.assertEquals(false, ev.valuePresent)
        ev.last { Assert.assertEquals(null, it) }
        ev.trigger("1")
        Assert.assertEquals(true, ev.valuePresent)
        ev.last { Assert.assertEquals("1", it) }
    }

    @Test
    fun testLastOptional() {
        val ev = Stream<String?>()
        Assert.assertEquals(false, ev.valuePresent)
        ev.last { Assert.assertEquals(null, it) }
        ev.trigger(null)
        Assert.assertEquals(true, ev.valuePresent)
        ev.last { Assert.assertEquals(null, it) }
        ev.trigger("1")
        Assert.assertEquals(true, ev.valuePresent)
        ev.last { Assert.assertEquals("1", it) }
    }

    @Test
    fun testDistinct() {
        val ev = Stream<String>()
        var result = emptyList<String>()
        ev.distinct().subscribe(replay = true) { result += it }
        ev
            .trigger("1")
            .trigger("2").trigger("2")
            .trigger("3").trigger("3").trigger("3")
        Assert.assertEquals(listOf("1", "2", "3"), result)
        ev.dispose()
        AllocationTracker.report(true)
    }

    @Test
    fun testDistinctNull() {
        val ev = Stream<String?>()
        var result = emptyList<String?>()
        ev.distinct().subscribe(replay = true) { result += it }
        ev
            .trigger(null).trigger(null)
            .trigger("1")
            .trigger("2").trigger("2")
            .trigger(null)
            .trigger("3").trigger("3").trigger("3")
            .trigger(null).trigger(null)
        Assert.assertEquals(listOf(null, "1", "2", null, "3", null), result)
        ev.dispose()
        AllocationTracker.report(true)
    }

    @Test
    fun testCombine2() {
        val a = Stream<String>()
        val b = Stream<String?>()
        var result = emptyList<Tuple2<String, String?>>()
        combine(a, b).distinct().subscribe { result += it }
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
        b.dispose()
        AllocationTracker.report(true)
    }

    @Test
    fun testFold() {
        val ev1 = Stream<String?>()
        var result = Pair<String?, String?>("", null)
        val sub = ev1
            .trigger(null)
            .fold(Pair<String?, String?>(null, null)) { (_, old), new ->  Pair(old, new) }
            .subscribe(replay = true) { pair -> result = pair }

        Assert.assertEquals(Pair(null, null), result)
        ev1.trigger("1")
        Assert.assertEquals(Pair(null, "1"), result)
        ev1.trigger("2")
        Assert.assertEquals(Pair("1", "2"), result)
        ev1.trigger("3")
        Assert.assertEquals(Pair("2", "3"), result)
        ev1.trigger(null)
        Assert.assertEquals(Pair("3", null), result)
        ev1.dispose()
        sub.dispose()
        AllocationTracker.report(true)
    }

    @Test
    fun noSubscriptionsShouldBeInMemory() {
        AllocationTracker.report(true)
    }
}