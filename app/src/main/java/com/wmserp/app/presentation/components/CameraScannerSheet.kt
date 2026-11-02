package com.wmserp.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wmserp.app.R
import com.wmserp.app.presentation.scan.CameraScannerPane
import com.wmserp.app.presentation.theme.WmsTheme

/**
 * Camera scanning as a modal sheet over the current screen instead of a small preview squeezed
 * above the list: a large viewfinder with the frame and scan line, a hint, a close button and an
 * optional [header] that tells the picker what to scan next. Dismissing the sheet stops the camera.
 */
@Composable
fun CameraScannerSheet(
    title: String,
    onDismiss: () -> Unit,
    onBarcode: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    scanning: Boolean = true,
    hint: String = stringResource(R.string.camera_sheet_hint),
    header: @Composable ColumnScope.() -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier.testTag("camera_sheet")) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("camera_sheet_close")) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.camera_sheet_close))
                }
            }
            header()
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF15152A)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraScannerPane(modifier = Modifier.fillMaxSize(), onBarcode = onBarcode)
                    ScanFrameOverlay(accent = WmsTheme.colors.gradientEnd, animate = scanning)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(hint, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
