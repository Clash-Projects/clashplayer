package com.clashplayer.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashplayer.diagnostics.DiagnosticsManager
import com.clashplayer.diagnostics.LogEntry
import com.clashplayer.diagnostics.LogLevel
import com.clashplayer.ui.components.ObsidianPanel
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim
import com.clashplayer.ui.theme.Mono
import com.clashplayer.ui.theme.ObsidianRaised
import com.clashplayer.ui.theme.Phosphor
import com.clashplayer.ui.theme.Signal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val logs by DiagnosticsManager.logsFlow.collectAsState()
    val hasCrash = remember { DiagnosticsManager.hasCrashReports(context) }
    var showCrashDetails by remember { mutableStateOf(false) }
    val latestCrashReport = remember { DiagnosticsManager.getLatestCrashReport(context) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Diagnostics & Crash Logs",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Ink
                    )
                    Text(
                        text = "Audio pipeline telemetry, hardware state & crash reports",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ObsidianRaised)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Ink
                    )
                }
            }

            // Crash Alert Banner (if crash exists)
            if (hasCrash && latestCrashReport != null) {
                ObsidianPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCrashDetails = !showCrashDetails },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Signal,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Fatal Crash Recorded on Previous Run",
                                color = Signal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (showCrashDetails) "Tap to collapse stack trace" else "Tap to inspect crash stack trace",
                                color = InkDim,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                if (showCrashDetails) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.8f))
                            .border(1.dp, Signal.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    text = latestCrashReport,
                                    fontFamily = Mono,
                                    fontSize = 10.sp,
                                    color = Signal.copy(alpha = 0.9f),
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // Quick Stats HUD
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(label = "Driver", value = DiagnosticsManager.activeDriverName, modifier = Modifier.weight(1f))
                StatCard(label = "Rate", value = "${DiagnosticsManager.activeSampleRate / 1000}k", modifier = Modifier.weight(1f))
                StatCard(label = "DVC", value = if (DiagnosticsManager.isDvcActive) "+12dB" else "OFF", modifier = Modifier.weight(1f))
                StatCard(label = "Underruns", value = "${DiagnosticsManager.underrunCount}", modifier = Modifier.weight(1f))
            }

            // Action Toolbar: Export & Share, Copy, Clear
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        DiagnosticsManager.shareDiagnostics(context)
                    },
                    modifier = Modifier.weight(1.4f),
                    colors = ButtonDefaults.buttonColors(containerColor = Phosphor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Export Logs",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                OutlinedButton(
                    onClick = {
                        val report = DiagnosticsManager.generateSystemReport(context) + "\n\n" +
                                (DiagnosticsManager.getLatestCrashReport(context) ?: "") + "\n\n" +
                                logs.joinToString("\n") { it.toFormattedString() }

                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("ClashPlayer Diagnostics", report))
                        Toast.makeText(context, "Copied diagnostic logs to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = Ink,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", color = Ink, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        DiagnosticsManager.clearLogs(context)
                        Toast.makeText(context, "Diagnostics logs cleared", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        tint = InkDim,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", color = InkDim, fontSize = 12.sp)
                }
            }

            // Monospace Terminal Console
            ObsidianPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No log entries recorded yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs.reversed()) { entry ->
                            LogItemRow(entry = entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    ObsidianPanel(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 10.sp, color = InkDim)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Phosphor
            )
        }
    }
}

@Composable
private fun LogItemRow(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.CRASH -> Signal
        LogLevel.ERROR -> Color(0xFFFF5252)
        LogLevel.WARN -> Ember
        LogLevel.AUDIO -> Phosphor
        LogLevel.INFO -> Color(0xFF69F0AE)
        LogLevel.DEBUG -> InkDim
    }

    val timeStr = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = timeStr,
                fontFamily = Mono,
                fontSize = 9.sp,
                color = InkDim.copy(alpha = 0.6f),
                modifier = Modifier.width(66.dp)
            )
            Text(
                text = "[${entry.level.label.take(1)}]",
                fontFamily = Mono,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = levelColor,
                modifier = Modifier.width(22.dp)
            )
            Text(
                text = entry.message,
                fontFamily = Mono,
                fontSize = 10.sp,
                color = if (entry.level == LogLevel.CRASH || entry.level == LogLevel.ERROR) levelColor else Ink,
                lineHeight = 14.sp,
                modifier = Modifier.weight(1f)
            )
        }

        if (entry.throwableStackTrace != null) {
            Text(
                text = entry.throwableStackTrace,
                fontFamily = Mono,
                fontSize = 9.sp,
                color = Signal.copy(alpha = 0.8f),
                lineHeight = 12.sp,
                modifier = Modifier.padding(start = 88.dp, top = 2.dp)
            )
        }
    }
}
