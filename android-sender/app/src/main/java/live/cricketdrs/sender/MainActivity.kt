package live.cricketdrs.sender

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import live.cricketdrs.sender.update.UpdateGate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    private val client = DrsClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        client.connect()

        setContent {
            SenderScreen(client)
        }
    }

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
    }
}

private val Navy = Color(0xFF1E3C72)
private val NavyDeep = Color(0xFF2A5298)
private val GreenOn = Color(0xFF4CAF50)
private val RedOff = Color(0xFFF44336)

@Composable
fun SenderScreen(client: DrsClient) {
    val context = LocalContext.current
    val localOn by client.localConnected.collectAsState()
    val liveOn by client.liveConnected.collectAsState()
    val enabled by client.sendingEnabled.collectAsState()
    val error by client.lastError.collectAsState()
    val connected = localOn || liveOn
    var updateTick by remember { mutableIntStateOf(0) }

    UpdateGate(auto = true, manualTrigger = updateTick)

    DisposableEffect(error) {
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
        }
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Navy, NavyDeep)))
            .systemBarsPadding()
            .background(Color(0xF5FFFFFF))
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Sender",
                color = Navy,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clickable { updateTick++ }
            )
            Switch(
                checked = enabled,
                onCheckedChange = { client.setSendingEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = GreenOn,
                    uncheckedTrackColor = Color(0xFFCFD8DC)
                )
            )
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (connected) GreenOn else RedOff)
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columns = remember(maxWidth, maxHeight) {
                when {
                    maxHeight < 500.dp -> 7
                    maxWidth >= 700.dp -> 5
                    else -> 4
                }
            }
            val rows = ceil(DRS_EVENTS.size / columns.toFloat()).toInt()
            val chunks = DRS_EVENTS.chunked(columns)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(rows) { rowIndex ->
                    val rowItems = chunks.getOrNull(rowIndex).orEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowItems.forEach { event ->
                            EventButton(
                                event = event,
                                enabled = enabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                client.sendEvent(event.code)
                            }
                        }
                        repeat(columns - rowItems.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventButton(
    event: DrsEvent,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Navy,
            contentColor = Color.White,
            disabledContainerColor = Navy.copy(alpha = 0.45f),
            disabledContentColor = Color.White.copy(alpha = 0.7f)
        ),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Text(
            text = event.label,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
