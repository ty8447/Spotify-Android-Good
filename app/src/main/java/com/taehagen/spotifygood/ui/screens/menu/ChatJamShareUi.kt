package com.taehagen.spotifygood.ui.screens.menu

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Secondary Invite sheet reached from Jam details. Nothing is shared until a real link exists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JamShareInviteSheet(onDismiss: () -> Unit, onOpenQr: () -> Unit, shareUrl: String? = null) {
    val context = LocalContext.current
    val selectedUrl = JamInviteLinks.preferred(shareUrl)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = JamUi.background,
        dragHandle = { BottomSheetDefaults.DragHandle(color = JamUi.muted) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(19.dp),
        ) {
            Text("Invite friends to your Jam", color = JamUi.white,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Button(onClick = {
                selectedUrl?.let { url ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, "Invite to your Jam")) }
                }
            }, enabled = selectedUrl != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = JamUi.green, contentColor = JamUi.black,
                    disabledContainerColor = JamUi.green.copy(alpha = 0.58f),
                    disabledContentColor = JamUi.black),
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(50)) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(9.dp))
                Text("Share link", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (shareUrl == null) "Waiting for Spotify to supply this Jam's join link."
                else "The QR and Share link use the same invitation address.",
                color = JamUi.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            HorizontalDivider(color = JamUi.divider)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Auto-invite people nearby", color = JamUi.white,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("People on your WiFi or nearby with Bluetooth access enabled will be invited in Spotify.",
                        color = JamUi.muted, fontSize = 12.sp)
                }
                Icon(Icons.Rounded.WifiTethering, contentDescription = null,
                    tint = JamUi.green, modifier = Modifier.size(31.dp))
            }
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpenQr)
                .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Your Jam QR code", color = JamUi.white, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(if (selectedUrl == null) "Loading invitation link…" else "Tap to enlarge QR code",
                        color = JamUi.muted, fontSize = 12.sp)
                }
                Box(Modifier.size(78.dp)
                    .background(Color.White.copy(alpha = 0.88f), RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center) {
                    if (selectedUrl != null) {
                        JamQrCode(selectedUrl, 78)
                    } else {
                        Icon(Icons.Rounded.QrCode2, contentDescription = "Waiting for Jam link",
                            tint = JamUi.black, modifier = Modifier.size(54.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** A real QR code generated entirely on this device from the current verified Jam link. */
@Composable
private fun JamQrCode(url: String, sizeDp: Int) {
    val matrix = remember(url) { runCatching { JamQrEncoder.encode(url) }.getOrNull() }
    if (matrix == null) {
        Icon(Icons.Rounded.QrCode2, contentDescription = "Jam QR code unavailable",
            tint = JamUi.muted, modifier = Modifier.size(sizeDp.dp))
        return
    }
    Canvas(Modifier.size(sizeDp.dp).background(Color.White, RoundedCornerShape(4.dp))) {
        // Four white modules on each side are required for reliable QR scanning.
        val module = size.minDimension / (JamQrEncoder.SIZE + 8).toFloat()
        for (y in 0 until JamQrEncoder.SIZE) for (x in 0 until JamQrEncoder.SIZE) {
            if (matrix[y][x]) drawRect(Color.Black,
                topLeft = Offset((x + 4) * module, (y + 4) * module),
                size = Size(module + 0.03f, module + 0.03f))
        }
    }
}

@Composable
internal fun JamQrCodeDialog(shareUrl: String?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .background(JamUi.background, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Scan QR code", color = JamUi.white, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Box(Modifier.size(190.dp).background(Color.White, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center) {
                if (shareUrl != null) JamQrCode(shareUrl, 190)
                else Icon(Icons.Rounded.QrCode2, contentDescription = "Waiting for Jam link",
                    tint = JamUi.muted, modifier = Modifier.size(90.dp))
            }
            Text(if (shareUrl != null) "Scan with your camera to join this Jam."
                else "Waiting for Spotify to supply the current Jam invitation link.",
                color = JamUi.muted, textAlign = TextAlign.Center, fontSize = 14.sp)
            Button(onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = JamUi.green, contentColor = JamUi.black),
                shape = RoundedCornerShape(50)) {
                Text("Dismiss", fontWeight = FontWeight.Bold)
            }
        }
    }
}
