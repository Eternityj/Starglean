package com.starglean.app.ui.space

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.abs

/** Compose owns reading/navigation state; one depth-buffered GL surface owns the whole sky. */
@Composable
internal fun CelestialScene(
    modifier: Modifier = Modifier,
    selectedInstanceId: String?,
    paused: Boolean,
    motionEnabled: Boolean,
    snapshot: HomeSkySnapshot = HomeSkySnapshot.Empty,
    onSelection: (String?) -> Unit,
    onStarTap: (String, Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val selectionCallback = rememberUpdatedState(onSelection)
    val starCallback = rememberUpdatedState(onStarTap)
    val view = remember(context) {
        CelestialView(context, snapshot).apply {
            selectionListener = { selectionCallback.value(it) }
            starListener = { instanceId, starIndex -> starCallback.value(instanceId, starIndex) }
        }
    }
    DisposableEffect(view, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.onPause()
        }
    }
    AndroidView(
        factory = { view },
        modifier = modifier,
        update = { it.updateScene(snapshot, selectedInstanceId, paused, motionEnabled) },
    )
}

internal class CelestialView(context: Context, initialSnapshot: HomeSkySnapshot) : GLSurfaceView(context) {
    var selectionListener: (String?) -> Unit = {}
    var starListener: (String, Int) -> Unit = { _, _ -> }
    private var snapshot = initialSnapshot
    private val scene = CelestialRenderer(context.resources, resources.displayMetrics.density, initialSnapshot)
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var previousX = 0f
    private var previousY = 0f
    private var moved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastSelection: String? = null
    private var lastPaused = false
    private var lastMotion = true

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(DepthConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(scene)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = if (snapshot.figures.isEmpty())
            "Your planet and starry sky. Complete a constellation in My constellations to bring it here. Drag to rotate the planet."
        else "Planet and constellations. Drag left or right to rotate, tap a constellation to explore, then tap a star to open its entry."
        scene.onSelection = { instanceId -> post {
            if (instanceId == null || snapshot.indexOf(instanceId) != null) selectionListener(instanceId)
        } }
        scene.onStarTap = { instanceId, starIndex -> post {
            val figure = snapshot.figures.firstOrNull { it.instanceId == instanceId }
            if (lastSelection == instanceId && figure != null && starIndex in figure.geometry.points.indices) {
                starListener(instanceId, starIndex)
            }
        } }
    }

    fun updateScene(next: HomeSkySnapshot, selectedInstanceId: String?, paused: Boolean, motionEnabled: Boolean) {
        if (next !== snapshot || selectedInstanceId != lastSelection || paused != lastPaused || motionEnabled != lastMotion) {
            val accessibilityChanged = next !== snapshot || selectedInstanceId != lastSelection
            snapshot = next
            lastSelection = selectedInstanceId
            lastPaused = paused
            lastMotion = motionEnabled
            contentDescription = if (next.figures.isEmpty())
                "Your planet and starry sky. Complete a constellation in My constellations to bring it here. Drag to rotate the planet."
            else "Planet and constellations. Drag left or right to rotate, tap a constellation to explore, then tap a star to open its entry."
            queueEvent {
                scene.updateSnapshot(next)
                scene.updateState(next.indexOf(selectedInstanceId), paused, motionEnabled)
            }
            if (accessibilityChanged) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                previousX = event.x
                previousY = event.y
                moved = false
                queueEvent { scene.beginDrag() }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                if (abs(event.x - downX) + abs(event.y - downY) > touchSlop) moved = true
                val dx = (event.x - previousX) / width.coerceAtLeast(1)
                val dy = (event.y - previousY) / height.coerceAtLeast(1)
                previousX = event.x
                previousY = event.y
                if (moved) queueEvent { scene.drag(dx, dy) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val velocity = (tracker?.xVelocity ?: 0f) / width.coerceAtLeast(1)
                val x = event.x
                val y = event.y
                val wasTap = !moved
                queueEvent {
                    scene.endDrag(if (wasTap) 0f else velocity)
                    if (wasTap) scene.pick(x, y)
                }
                if (wasTap) performClick()
                tracker?.recycle()
                tracker = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                queueEvent { scene.endDrag(0f) }
                tracker?.recycle()
                tracker = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        snapshot.figures.forEachIndexed { index, constellation ->
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(0x01010000 + index, "Explore ${constellation.name}"))
        }
        snapshot.figures.firstOrNull { it.instanceId == lastSelection }?.let { figure ->
            figure.geometry.points.indices.forEach { starIndex ->
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(0x01010100 + starIndex,
                    "Open star ${starIndex + 1} in ${figure.name}"))
            }
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(0x01010011, "Back to the sky"))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val index = action - 0x01010000
        when {
            index in snapshot.figures.indices -> selectionListener(snapshot.figures[index].instanceId)
            action - 0x01010100 in (snapshot.figures.firstOrNull { it.instanceId == lastSelection }?.geometry?.points?.indices ?: IntRange.EMPTY) ->
                starListener(checkNotNull(lastSelection), action - 0x01010100)
            action == 0x01010011 -> selectionListener(null)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }
}

/** Request real depth storage; use MSAA where supported without excluding older devices. */
private class DepthConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (samples in intArrayOf(4, 0)) {
            val attributes = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_RENDERABLE_TYPE, 4,
                EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                EGL10.EGL_SAMPLES, samples, EGL10.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (egl.eglChooseConfig(display, attributes, configs, 1, count) && count[0] > 0) {
                return requireNotNull(configs[0])
            }
        }
        error("A depth-buffered OpenGL ES 2 configuration is required")
    }
}
