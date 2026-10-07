package net.tspigot.radio.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessagesTest {
    private fun message(id: String) = ChatMessage(id, ChatMessageKind.Normal, id, 1L)

    @Test
    fun historyAndLiveMessagesHaveUniqueKeys() = runBlocking {
        val store = ChatMessageStore()
        store.post(listOf(message("a"), message("b")))
        store.post(listOf(message("b"), message("c")))
        store.applyNextBatch()
        assertEquals(listOf("a", "b", "c"), store.messages.map { it.id })
    }

    @Test
    fun reconnectResetDiscardsOldQueuedMessages() = runBlocking {
        val store = ChatMessageStore()
        store.post(listOf(message("existing")))
        store.applyNextBatch()
        store.post(listOf(message("queued-before-reset")))
        store.reset()
        store.post(listOf(message("new-history")))
        val result = store.applyNextBatch()
        assertTrue(result.cleared)
        assertEquals(1, result.addedCount)
        assertEquals(listOf("new-history"), store.messages.map { it.id })
    }

    @Test
    fun newMessagesStillPublishWhenTheListIsFull() = runBlocking {
        val store = ChatMessageStore()
        store.post((0..999).map { message(it.toString()) })
        store.applyNextBatch()
        store.post(listOf(message("1000")))
        val result = store.applyNextBatch()
        assertEquals(1000, store.messages.size)
        assertEquals("1", store.messages.first().id)
        assertEquals("1000", store.messages.last().id)
        assertEquals(1, result.addedCount)
    }

    @Test
    fun blankServerIdsGetUniqueFallbacks() {
        val json = JSONObject().put("type", "message").put("id", "")
        assertNotEquals(parseChatMessage(json)!!.id, parseChatMessage(json)!!.id)
    }

    @Test
    fun slashCommandsPreserveMultiWordArguments() {
        assertEquals(ParsedCommand("name", "two words"), parseSlashCommand("/NAME   two words"))
        assertNull(parseSlashCommand("/   "))
    }

    @Test
    fun outgoingLikeAndPlainMessageKeepTheirProtocol() {
        val like = JSONObject(buildOutgoingPayload("/like")!!.json)
        assertEquals("command", like.getString("type"))
        assertEquals("like", like.getString("command"))
        assertEquals(0, like.getJSONArray("args").length())
        val plain = JSONObject(buildOutgoingPayload("  hello  ")!!.json)
        assertEquals("message", plain.getString("type"))
        assertEquals("hello", plain.getString("text"))
        assertNull(buildOutgoingPayload("  "))
    }
}
