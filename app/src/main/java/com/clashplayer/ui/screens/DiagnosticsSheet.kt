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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashplayer.diagnostics.DiagnosticsManager
import com.clashplayer.diagnostics.LogEntry
import com.clashplayer.diagnostics.LogLevel
import com.clashplayer.ui.components.FrostedGlassBox
import com.clashplayer.ui.theme.DarkSurface
import com.clashplayer.ui.theme.GlassBorder
import com.clashplayer.ui.theme.NeonCyan
import com.clashplayer.ui.theme.NeonPink
import com.clashplayer.ui.theme.NeonPurple
import com.clashplayer.ui.theme.TextPrimary
import com.clashplayer.ui.theme.TextSecondary
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
                        color = TextPrimary
                    )
                    Text(
                        text = "Audio pipeline telemetry, hardware state & crash reports",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(DarkSurface)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextPrimary
                    )
                }
            }

            // Crash Alert Banner (if crash exists)
            if (hasCrash && latestCrashReport != null) {
                FrostedGlassBox(
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
                            tint = NeonPink,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Fatal Crash Recorded on Previous Run",
                                color = NeonPink,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (showCrashDetails) "Tap to collapse stack trace" else "Tap to inspect crash stack trace",
                                color = TextSecondary,
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
                            .border(1.dp, NeonPink.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    text = latestCrashReport,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = NeonPink.copy(alpha = 0.9f),
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
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = DarkSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Export Logs",
                        color = DarkSurface,
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
                        tint = TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", color = TextPrimary, fontSize = 12.sp)
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
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", color = TextSecondary, fontSize = 12.sp)
                }
            }

            // Monospace Terminal Console
            FrostedGlassBox(
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
                            color = TextSecondary
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
    FrostedGlassBox(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 10.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = NeonCyan
            )
        }
    }
}

@Composable
private fun LogItemRow(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.CRASH -> NeonPink
        LogLevel.ERROR -> Color(0xFFFF5252)
        LogLevel.WARN -> Color(0xFFFFD740)
        LogLevel.AUDIO -> NeonCyan
        LogLevel.INFO -> Color(0xFF69F0AE)
        LogLevel.DEBUG -> TextSecondary
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
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = TextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.width(66.dp)
            )
            Text(
                text = "[${entry.level.label.take(1)}]",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = levelColor,
                modifier = Modifier.width(22.dp)
            )
            Text(
                text = entry.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = if (entry.level == LogLevel.CRASH || entry.level == LogLevel.ERROR) levelColor else TextPrimary,
                lineHeight = 14.sp,
                modifier = Modifier.weight(1f)
            )
        }

        if (entry.throwableStackTrace != null) {
            Text(
                text = entry.throwableStackTrace,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = NeonPink.copy(alpha = 0.8f),
                lineHeight = 12.sp,
                modifier = Modifier.padding(start = 88.dp, top = 2.dp)
            )
        }
    }
}
