package tachiyomi.presentation.core.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class SheetValue {
    Hidden,
    PartiallyExpanded,
    Expanded,
}

/**
 * A bottom sheet that can be resized by dragging a handle at the top.
 * Supports three states: Hidden, PartiallyExpanded (50%), and Expanded (90%).
 */
@Composable
fun ResizableSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    initialValue: SheetValue = SheetValue.PartiallyExpanded,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    
    var screenHeight by remember { mutableIntStateOf(0) }
    
    // Map SheetValue to Int for AnchoredDraggableState
    val stateMapping = remember {
        mapOf(
            SheetValue.Hidden to 2,
            SheetValue.PartiallyExpanded to 1,
            SheetValue.Expanded to 0,
        )
    }
    
    val initialStateValue = stateMapping[initialValue] ?: 1
    
    val anchoredDraggableState = rememberSaveable(
        saver = AnchoredDraggableState.Saver(),
    ) {
        AnchoredDraggableState(initialValue = initialStateValue)
    }
    
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = anchoredDraggableState,
        positionalThreshold = { distance: Float -> distance * 0.5f },
        animationSpec = sheetAnimationSpec,
    )
    
    val internalOnDismissRequest: () -> Unit = {
        scope.launch {
            anchoredDraggableState.animateTo(2) // 2 = Hidden
        }
        Unit
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                screenHeight = size.height
                val anchors = DraggableAnchors {
                    0 at size.height * 0.1f  // Expanded
                    1 at size.height * 0.5f  // PartiallyExpanded
                    2 at size.height.toFloat()  // Hidden
                }
                anchoredDraggableState.updateAnchors(anchors)
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Scrim (background overlay)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = internalOnDismissRequest,
                )
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
        )
        
        Surface(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = {},
                )
                .then(modifier)
                .offset {
                    IntOffset(
                        0,
                        anchoredDraggableState.requireOffset().roundToInt(),
                    )
                }
                .nestedScroll(
                    remember(anchoredDraggableState) {
                        SheetNestedScrollConnection(anchoredDraggableState)
                    },
                )
                .navigationBarsPadding()
                .statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            Column {
                // Drag handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .anchoredDraggable(
                            state = anchoredDraggableState,
                            orientation = Orientation.Vertical,
                            enabled = true,
                            flingBehavior = flingBehavior,
                        )
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(2.dp),
                            ),
                    )
                }
                
                // Content
                content()
            }
            
            BackHandler(
                enabled = anchoredDraggableState.currentValue != 2, // 2 = Hidden
                onBack = internalOnDismissRequest,
            )
        }
        
        LaunchedEffect(anchoredDraggableState) {
            if (initialValue != SheetValue.Hidden) {
                anchoredDraggableState.animateTo(initialStateValue)
            }
            snapshotFlow { anchoredDraggableState.settledValue }
                .drop(1)
                .filter { it == 2 } // 2 = Hidden
                .collectLatest {
                    onDismissRequest()
                }
        }
    }
}

private class SheetNestedScrollConnection(
    private val state: AnchoredDraggableState<Int>,
) : NestedScrollConnection {
    override fun onPreScroll(
        available: androidx.compose.ui.geometry.Offset,
        source: NestedScrollSource,
    ): androidx.compose.ui.geometry.Offset {
        val delta = available.y
        return if (delta < 0 && source == NestedScrollSource.UserInput) {
            state.dispatchRawDelta(delta).toOffset()
        } else {
            androidx.compose.ui.geometry.Offset.Zero
        }
    }
    
    override fun onPostScroll(
        consumed: androidx.compose.ui.geometry.Offset,
        available: androidx.compose.ui.geometry.Offset,
        source: NestedScrollSource,
    ): androidx.compose.ui.geometry.Offset {
        return if (source == NestedScrollSource.UserInput) {
            state.dispatchRawDelta(available.y).toOffset()
        } else {
            androidx.compose.ui.geometry.Offset.Zero
        }
    }
    
    private fun Float.toOffset(): androidx.compose.ui.geometry.Offset =
        androidx.compose.ui.geometry.Offset(0f, this)
}

private val sheetAnimationSpec = tween<Float>(durationMillis = 350)
