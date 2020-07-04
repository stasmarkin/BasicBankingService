package me.stasmarkin.simplebank

import me.stasmarkin.simplebank.util.RetryAfterAckBlockingMessageQueue
import org.junit.Assert.*
import org.junit.Test

internal class RetryAfterAckBlockingMessageQueueTest {


    @Test
    fun baseOfferExamineTest() {
        val q = RetryAfterAckBlockingMessageQueue<Int>(3)

        assertTrue(q.offer(1))
        assertTrue(q.offer(2))
        assertTrue(q.offer(3))

        assertFalse(q.offer(4))
        assertFalse(q.offer(5))

        val (s1, el1) = q.poll()!!
        assertFalse(q.offer(4))
        val (s2, el2) = q.poll()!!
        assertFalse(q.offer(4))
        val (s3, el3) = q.poll()!!
        assertFalse(q.offer(4))

        assertNull(q.poll())

        assertEquals(1, el1)
        assertEquals(2, el2)
        assertEquals(3, el3)
        assertTrue(s1 < s2)
        assertTrue(s2 < s3)

        q.ack(s2, el2)
        assertNull(q.poll())
        assertTrue(q.offer(4))

        val (s4, el4) = q.poll()!!
        assertEquals(4, el4)
        assertTrue(s3 < s4)

        assertFalse(q.offer(5))

    }

    @Test
    fun backTest() {
        val q = RetryAfterAckBlockingMessageQueue<Int>(3)

        assertTrue(q.offer(1))
        assertTrue(q.offer(2))
        assertTrue(q.offer(3))
        assertFalse(q.offer(4))

        val (s1, el1) = q.poll()!!
        val (s2, el2) = q.poll()!!
        val (s3, el3) = q.poll()!!
        assertFalse(q.offer(4))
        assertNull(q.poll())

        q.nack(s1, el1)
        assertFalse(q.offer(4))
        assertNull(q.poll())

        q.nack(s3, el3)
        assertFalse(q.offer(4))
        assertNull(q.poll())

        q.ack(s2, el2)
        val (s4, el4) = q.poll()!!
        val (s5, el5) = q.poll()!!
        if (el1 == el4 && el3 == el5) {
            assertTrue(s4 > s1)
            assertTrue(s5 > s3)
        } else if (el3 == el4 && el1 == el5) {
            assertTrue(s4 > s3)
            assertTrue(s5 > s1)
        } else {
            assertTrue("el4 and el5 doesn't match el1 and el3", false)
        }
        assertNull(q.poll())
        assertTrue(q.offer(4))
        assertFalse(q.offer(5))

        assertNotNull(q.poll())
        assertFalse(q.offer(5))
    }
}