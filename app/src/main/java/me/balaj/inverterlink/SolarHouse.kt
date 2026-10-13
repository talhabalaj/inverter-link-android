package me.balaj.inverterlink

import android.animation.ValueAnimator
import android.content.Context
import android.opengl.Matrix
import android.view.Choreographer
import android.view.MotionEvent
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.*
import com.google.android.filament.utils.*
import java.nio.ByteBuffer

@Composable
fun SolarHouse(reading: Reading?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var view by remember { mutableStateOf<HouseView?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view?.resumeRendering()
                Lifecycle.Event.ON_STOP -> view?.pauseRendering()
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            view?.pauseRendering()
        }
    }
    Box(modifier) {
        AndroidView(
            factory = { HouseView(it).also { v -> view = v } },
            update = {
                it.reading = reading
                it.updateEnergy()
            },
            onRelease = { it.pauseRendering() },
            modifier = Modifier.fillMaxSize(),
        )
        reading?.let { r ->
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SceneLabel("Solar", "${(r.values["PV charging power"]?:0.0).formatNumber()} W")
                val net =
                    (r.values["Battery charge current"] ?: 0.0) -
                        (r.values["Battery discharge current"] ?: 0.0)
                SceneLabel(
                    if (net > 0) "Charging" else "Battery",
                    if (net == 0.0) "Idle"
                    else
                        "~${(kotlin.math.abs(net)*(r.values["Battery voltage"]?:0.0)).formatNumber()} W",
                )
                SceneLabel("Grid", if (r.mode == "Grid") "Supplying" else "Standby")
            }
        }
    }
}

@Composable
private fun SceneLabel(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .9f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private class HouseView(context: Context) : TextureView(context), Choreographer.FrameCallback {
    companion object {
        init {
            Utils.init()
        }
    }

    private val viewer =
        ModelViewer(
            this,
            manipulator =
                Manipulator.Builder()
                    .orbitHomePosition(1.5f, 1.25f, 1.9f)
                    .targetPosition(0f, 0f, 0f)
                    .build(Manipulator.Mode.ORBIT),
        )
    var reading: Reading? = null
    private val clock = Choreographer.getInstance()
    private var active = false
    private var released = false
    private var lastFrame = 0L
    private val nodes = mutableMapOf<String, Int>()
    private val routes =
        listOf(
            floatArrayOf(.7f, 1.96f, -.35f) to floatArrayOf(-.9f, .45f, .95f),
            floatArrayOf(-1.45f, .5f, 1.05f) to floatArrayOf(-.9f, .45f, .95f),
            floatArrayOf(-.9f, .45f, .95f) to floatArrayOf(.6f, .65f, .77f),
            floatArrayOf(1.55f, 1.5f, -1.04f) to floatArrayOf(.6f, .65f, .77f),
        )

    init {
        contentDescription = "Interactive solar home"
        isFocusable = true
        viewer.loadModelGlb(
            ByteBuffer.wrap(context.assets.open("solar-house.glb").use { it.readBytes() })
        )
        viewer.transformToUnitCube(Float3(0f, 0f, 0f))
        viewer.scene.skybox = Skybox.Builder().color(.025f, .033f, .037f, 1f).build(viewer.engine)
        viewer.scene.indirectLight =
            IndirectLight.Builder()
                .irradiance(1, floatArrayOf(.4f, .4f, .4f))
                .intensity(25000f)
                .build(viewer.engine)
        viewer.asset?.entities?.forEach { entity ->
            viewer.asset?.getName(entity)?.let { nodes[it] = entity }
        }
        setOnTouchListener { _, event ->
            viewer.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            true
        }
        updateEnergy()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!released) resumeRendering()
    }

    override fun onDetachedFromWindow() {
        pauseRendering()
        released = true
        super.onDetachedFromWindow()
    }

    fun resumeRendering() {
        if (active || released) return
        active = true
        clock.postFrameCallback(this)
    }

    fun pauseRendering() {
        active = false
        clock.removeFrameCallback(this)
    }

    private fun transform(name: String, position: FloatArray, scale: FloatArray) {
        val entity = nodes[name] ?: return
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, position[0], position[1], position[2])
        Matrix.scaleM(matrix, 0, scale[0], scale[1], scale[2])
        val tm = viewer.engine.transformManager
        tm.setTransform(tm.getInstance(entity), matrix)
    }

    private fun show(name: String, visible: Boolean) {
        val entity = nodes[name] ?: return
        if (visible) viewer.scene.addEntity(entity) else viewer.scene.remove(entity)
    }

    private fun tint(name: String, r: Float, g: Float, b: Float) {
        val entity = nodes[name] ?: return
        val manager = viewer.engine.renderableManager
        val instance = manager.getInstance(entity)
        if (instance != 0)
            manager
                .getMaterialInstanceAt(instance, 0)
                .setParameter("baseColorFactor", Colors.RgbaType.LINEAR, r, g, b, 1f)
    }

    fun updateEnergy() {
        if (released) return
        val r = reading
        val level = ((r?.values?.get("Reported battery") ?: 0.0) / 100).coerceIn(.01, 1.0).toFloat()
        transform(
            "battery-fill",
            floatArrayOf(-1.45f, .165f + .225f * level, 1.005f),
            floatArrayOf(.35f, .45f * level, .025f),
        )
        val load = (r?.values?.get("Load power") ?: 0.0) > 0
        val solar = (r?.values?.get("PV charging power") ?: 0.0) > 0
        show("window-light", load)
        if (load) tint("front-glass", .9f, .6f, .2f) else tint("front-glass", .1f, .14f, .16f)
        if (solar) tint("panel-cell", .08f, .32f, .55f) else tint("panel-cell", .04f, .14f, .27f)
        if (r != null) tint("battery-fill", .13f, .85f, .53f)
        else tint("battery-fill", .2f, .2f, .2f)
        for (route in routes.indices) for (dot in 0..5) show("flow_${route}_$dot", false)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!active || released) return
        clock.postFrameCallback(this)
        if (frameTimeNanos - lastFrame < 33_000_000L) return
        lastFrame = frameTimeNanos
        val r = reading
        val net =
            (r?.values?.get("Battery charge current") ?: 0.0) -
                (r?.values?.get("Battery discharge current") ?: 0.0)
        val enabled =
            booleanArrayOf(
                (r?.values?.get("PV charging power") ?: 0.0) > 0,
                net != 0.0,
                (r?.values?.get("Load power") ?: 0.0) > 0,
                r?.mode == "Grid",
            )
        for (route in routes.indices) for (dot in 0..5) {
            val visible = enabled[route] && ValueAnimator.areAnimatorsEnabled()
            show("flow_${route}_$dot", visible)
            if (visible) {
                var (start, end) = routes[route]
                if (route == 1 && net > 0) {
                    val swap = start
                    start = end
                    end = swap
                }
                val t = ((frameTimeNanos / 1e9 / 2.4 + dot / 6.0) % 1.0).toFloat()
                transform(
                    "flow_${route}_$dot",
                    FloatArray(3) { i -> start[i] + (end[i] - start[i]) * t },
                    floatArrayOf(.045f, .045f, .045f),
                )
            }
        }
        viewer.render(frameTimeNanos)
    }
}
