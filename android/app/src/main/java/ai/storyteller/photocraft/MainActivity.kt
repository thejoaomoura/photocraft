package ai.storyteller.photocraft

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ai.storyteller.photocraft.editor.EditorViewModel
import ai.storyteller.photocraft.ui.EditorScreen
import ai.storyteller.photocraft.ui.PhotoCraftTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The editor is always dark: light status/navigation bar icons, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) openFrom(intent)
        setContent { PhotoCraftTheme { EditorScreen(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** Android may kill a backgrounded app at any time: keep the document recoverable. */
    override fun onStop() {
        super.onStop()
        vm.autosave()
    }

    /** "Open with" / "Edit" from another app. */
    private fun openFrom(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) vm.open(uri)
    }
}
