package net.tspigot.radio

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.tspigot.radio.ui.activities.HistoryActivity
import net.tspigot.radio.ui.activities.MainActivity
import net.tspigot.radio.ui.viewmodel.ChatViewModel
import net.tspigot.radio.ui.viewmodel.HistoryViewModel
import net.tspigot.radio.ui.viewmodel.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewModelRecreationTest {
    @Test
    fun mainActivityRetainsPlayerChatAndDraft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var player: PlayerViewModel
            lateinit var chat: ChatViewModel
            scenario.onActivity { activity ->
                val provider = ViewModelProvider(activity)
                player = provider[PlayerViewModel::class.java]
                chat = provider[ChatViewModel::class.java]
                chat.updateInput("unsent draft")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val provider = ViewModelProvider(activity)
                assertSame(player, provider[PlayerViewModel::class.java])
                assertSame(chat, provider[ChatViewModel::class.java])
                assertEquals("unsent draft", chat.uiState.value.input)
            }
        }
    }
    
    @Test
    fun historyActivityRetainsFilter() {
        ActivityScenario.launch(HistoryActivity::class.java).use { scenario ->
            lateinit var history: HistoryViewModel
            scenario.onActivity { activity ->
                history = ViewModelProvider(activity)[HistoryViewModel::class.java]
                history.toggleShowAll()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(history, ViewModelProvider(activity)[HistoryViewModel::class.java])
                assertTrue(history.uiState.value.showAll)
            }
        }
    }
}
