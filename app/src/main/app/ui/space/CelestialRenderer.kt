package com.starglean.app.ui.space

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.starglean.app.R
import com.starglean.app.ui.space.CelestialGeometry.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A shared perspective camera, sphere mesh and depth buffer. Stars and their connecting
 * dust are world geometry, so limb occlusion and foreshortening do not depend on 2D layers.
 * All mutable renderer state belongs to the GL thread (input is delivered with queueEvent).
 */
internal class CelestialRenderer(private val resources: Resources, private val density: Float,
    initialSnapshot: HomeSkySnapshot = HomeSkySnapshot.Empty) : GLSurfaceView.Renderer {
    var onSelection: (String?) -> Unit = {}
    var onStarTap: (String, Int) -> Unit = { _, _ -> }
    private var snapshot = initialSnapshot
    private var width = 1
    private var height = 1
    private var lastFrame = 0L
    private var elapsed = 0f
    private var orbitPhase = .36f
    private var globeAngle = .4f
    private var yaw = 0f
    private var pitch = -.08f
    private var velocity = 0f
    private var touching = false
    private var paused = false
    private var motionEnabled = true
    private var selected: Int? = null
    private var focusIndex: Int? = null
    private var focusProgress = 0f
    private var focusAngle = 0f
    private var focusAxis = Vec3(0f, 1f, 0f)
    private var cygnusTargetRoll = 0f
    private val camera = Vec3(0f, 0f, 6.4f)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val base = FloatArray(16)
    private val focusedRotation = FloatArray(16)
    private val world = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val skyModel = FloatArray(16)
    private val skyMvp = FloatArray(16)
    private lateinit var planetProgram: Program
    private lateinit var atmosphereProgram: Program
    private lateinit var starProgram: Program
    private lateinit var filamentProgram: Program
    private lateinit var anchorProgram: Program
    private lateinit var fleckProgram: Program
    private lateinit var backgroundProgram: Program
    private var texture = 0
    private val sphere = SphereMesh(192, 128)
    private val quad = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val points = floats(FloatArray(2048))
    // Reuse one buffer per layer across all figures; no per-frame direct buffer allocations.
    private var filaments = floats(FloatArray((snapshot.figures.maxOfOrNull { it.filaments.size } ?: 1) * 5))
    private var flecks = floats(FloatArray((snapshot.figures.maxOfOrNull { it.flecks.size } ?: 1) * 5))
    private val randomStars = makeStarfield()
    private var projectedStars = emptyList<HomeStarHit>()
    private var projectedDust = emptyList<HomeStarHit>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glClearColor(.009f, .015f, .029f, 1f)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        planetProgram = Program(SPHERE_VERTEX, PLANET_FRAGMENT)
        atmosphereProgram = Program(SPHERE_VERTEX, ATMOSPHERE_FRAGMENT)
        starProgram = Program(STAR_VERTEX, STAR_FRAGMENT)
        filamentProgram = Program(STAR_VERTEX, FILAMENT_FRAGMENT)
        anchorProgram = Program(STAR_VERTEX, ANCHOR_FRAGMENT)
        fleckProgram = Program(STAR_VERTEX, FLECK_FRAGMENT)
        backgroundProgram = Program(BACKGROUND_VERTEX, BACKGROUND_FRAGMENT)
        texture = loadTexture()
        lastFrame = 0L
        Log.i(TAG, "Sphere mesh, material and depth-buffered constellations ready. GPU: ${glGetString(GL_RENDERER)}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        // Horizontal framing keeps the complete sphere near 80% of a portrait display.
        val halfWidth = if (aspect < .85f) .199f else .27f * aspect
        Matrix.frustumM(projection, 0, -halfWidth, halfWidth, -halfWidth / aspect, halfWidth / aspect, 1f, 40f)
        projection[9] = .10f
        Matrix.setLookAtM(view, 0, camera.x, camera.y, camera.z, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, .05f)
        lastFrame = now
        if (motionEnabled && !paused) elapsed += dt
        val focusTarget = if (selected != null) 1f else 0f
        if (motionEnabled) focusProgress += (focusTarget - focusProgress) * (1f - exp(-dt * 5.6f))
        else focusProgress = focusTarget
        if (abs(focusProgress - focusTarget) < .001f) focusProgress = focusTarget
        if (focusProgress == 0f && selected == null) focusIndex = null
        if (selected == null && focusProgress < .01f) {
            if (motionEnabled && !paused && !touching) {
                orbitPhase = (orbitPhase + dt * (2f * PI.toFloat() / 48f)) % (2f * PI.toFloat())
                globeAngle = (globeAngle + dt * (2f * PI.toFloat() / 160f)) % (2f * PI.toFloat())
            }
            if (!touching && motionEnabled && !paused) {
                yaw += velocity * dt
                velocity *= exp(-3.8f * dt)
            }
        }
        updateMatrices()
        glDepthMask(true)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        drawBackground()
        drawDistantStars()
        drawPlanet()
        drawAtmosphere()
        drawConstellations()
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    fun updateSnapshot(next: HomeSkySnapshot) {
        if (snapshot === next) return
        snapshot = next
        // Old hit targets and focused poses must never address a new instance by index.
        projectedStars = emptyList()
        projectedDust = emptyList()
        selected = null
        focusIndex = null
        focusProgress = 0f
        velocity = 0f
        val filamentCapacity = (next.figures.maxOfOrNull { it.filaments.size } ?: 1) * 5
        val fleckCapacity = (next.figures.maxOfOrNull { it.flecks.size } ?: 1) * 5
        if (filaments.capacity() < filamentCapacity) filaments = floats(FloatArray(filamentCapacity))
        if (flecks.capacity() < fleckCapacity) flecks = floats(FloatArray(fleckCapacity))
    }

    fun updateState(index: Int?, paused: Boolean, motionEnabled: Boolean) {
        this.paused = paused
        this.motionEnabled = motionEnabled
        if (selected == index) return
        selected = index
        velocity = 0f
        if (index != null) {
            focusIndex = index
            updateBaseMatrix()
            val selectedFigure = snapshot.figures[index]
            val center = transform(base, homeConstellationCenter(selectedFigure.geometry, orbitPhase))
            val direction = normalize(center)
            val target = normalize(Vec3(0f, .27f, 2f))
            focusAxis = normalize(cross(direction, target)).let {
                if (length(it) < .001f) Vec3(0f, 1f, 0f) else it
            }
            focusAngle = acos(dot(direction, target).coerceIn(-1f, 1f)) * 180f / PI.toFloat()
            if (selectedFigure.isCygnus) {
                val finalRotation = FloatArray(16)
                val finalWorld = FloatArray(16)
                Matrix.setRotateM(finalRotation, 0, focusAngle, focusAxis.x, focusAxis.y, focusAxis.z)
                Matrix.multiplyMM(finalWorld, 0, finalRotation, 0, base, 0)
                val figure = selectedFigure.geometry
                val tailAxis = transform(finalWorld, CelestialGeometry.rotateX(
                    CelestialGeometry.rotateY(Vec3(0f, 1f, 0f), orbitPhase + figure.phaseOffset),
                    figure.inclination,
                ))
                val delta = -PI.toFloat() * .5f - .22f - atan2(tailAxis.y, tailAxis.x)
                // Choose the shortest roll once, avoiding angle-wrap flips during the approach.
                cygnusTargetRoll = atan2(sin(delta), cos(delta))
            }
            focusProgress = 0f
        }
        Log.i(TAG, "Selection=$index; orbit ${if (index == null) "resumes" else "paused, focus begins"}")
    }

    fun beginDrag() { touching = true; velocity = 0f }

    fun drag(dx: Float, dy: Float) {
        if (selected != null || focusProgress > .01f) return
        yaw += dx * 3.5f
        pitch = (pitch + dy * 2.0f).coerceIn(-.65f, .65f)
        globeAngle += dx * 1.35f
    }

    fun endDrag(velocity: Float) {
        touching = false
        this.velocity = if (motionEnabled && selected == null) (velocity * 1.9f).coerceIn(-2f, 2f) else 0f
    }

    fun pick(x: Float, y: Float) {
        val hit = nearestHomeStar(projectedStars, x, y, selected, 30f * density)
        if (hit != null) {
            val instanceId = snapshot.figures[hit.index].instanceId
            if (selected == hit.index && focusProgress > .92f) onStarTap(instanceId, hit.starIndex) else onSelection(instanceId)
            return
        }
        // Visible mist selects its own figure, never opens a diary like a named star.
        // There are no invisible straight-line hit targets behind the new particulate paths.
        val dustHit = nearestHomeStar(projectedDust, x, y, selected, 12f * density)
        if (dustHit != null) {
            if (selected == null) onSelection(snapshot.figures[dustHit.index].instanceId)
            return
        }
        onSelection(null)
    }

    private fun updateBaseMatrix() {
        Matrix.setIdentityM(base, 0)
        Matrix.rotateM(base, 0, pitch * 180f / PI.toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(base, 0, yaw * 180f / PI.toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(base, 0, 67f, 0f, 0f, 1f)
    }

    private fun updateMatrices() {
        updateBaseMatrix()
        Matrix.setIdentityM(focusedRotation, 0)
        Matrix.rotateM(focusedRotation, 0, focusAngle * focusProgress, focusAxis.x, focusAxis.y, focusAxis.z)
        Matrix.multiplyMM(world, 0, focusedRotation, 0, base, 0)
    }

    private fun drawBackground() {
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        backgroundProgram.use()
        backgroundProgram.f1("uAspect", width.toFloat() / height)
        backgroundProgram.f1("uFocus", focusProgress)
        backgroundProgram.f1("uTime", elapsed)
        backgroundProgram.f2("uDrift", yaw * .035f, pitch * .035f)
        quad.position(0)
        backgroundProgram.attribute("aPosition", 2, 0, quad)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        backgroundProgram.disableAttributes()
    }

    private fun drawDistantStars() {
        Matrix.setIdentityM(skyModel, 0)
        Matrix.rotateM(skyModel, 0, yaw * 2.8f, 0f, 1f, 0f)
        Matrix.rotateM(skyModel, 0, pitch * 3.5f, 1f, 0f, 0f)
        Matrix.multiplyMM(skyMvp, 0, viewProjection, 0, skyModel, 0)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE)
        drawPoints(randomStars, 620, skyMvp, 15f * density, .8f * (1f - .6f * focusProgress), false)
    }

    private fun globeModel(scale: Float = 1f) {
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitch * 32f - 14f, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, (globeAngle + yaw * .7f) * 180f / PI.toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(model, 0, scale, scale, scale)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
    }

    private fun drawPlanet() {
        glEnable(GL_DEPTH_TEST)
        glDepthMask(true)
        glDisable(GL_BLEND)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        globeModel()
        planetProgram.use()
        planetProgram.matrix("uModel", model)
        planetProgram.matrix("uMvp", mvp)
        planetProgram.f3("uCamera", camera.x, camera.y, camera.z)
        planetProgram.f1("uFocus", focusProgress)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)
        glUniform1i(planetProgram.uniform("uTexture"), 0)
        sphere.draw(planetProgram)
    }

    private fun drawAtmosphere() {
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        globeModel(1.019f)
        atmosphereProgram.use()
        atmosphereProgram.matrix("uModel", model)
        atmosphereProgram.matrix("uMvp", mvp)
        atmosphereProgram.f3("uCamera", camera.x, camera.y, camera.z)
        atmosphereProgram.f1("uFocus", focusProgress)
        sphere.draw(atmosphereProgram)
    }

    private fun drawConstellations() {
        glEnable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE)
        val hitStars = ArrayList<HomeStarHit>(80)
        val dustTargets = ArrayList<HomeStarHit>()
        snapshot.figures.forEachIndexed { index, homeFigure ->
            val isFocus = focusIndex == index
            val alpha = if (isFocus) .78f + .22f * focusProgress else .76f * (1f - .89f * focusProgress)
            val orbitCenter = homeConstellationCenter(homeFigure.geometry, orbitPhase)
            val orientedCenter = transform(world, orbitCenter)
            val center = Vec3(orientedCenter.x * .88f, orientedCenter.y * .88f,
                orientedCenter.z * .88f + if (isFocus) 1.05f * focusProgress else 0f)
            val toEye = Vec3(camera.x - center.x, camera.y - center.y, camera.z - center.z)
            val facing = abs(dot(normalize(orientedCenter), normalize(toEye)))
            val visibility = ConstellationStyle.visibility(length(toEye), facing)
            val focus = if (isFocus) focusProgress else 0f
            val starVisibility = visibility.stars + (1f - visibility.stars) * focus
            val lineVisibility = visibility.lines + (1f - visibility.lines) * focus
            val cygnusRoll = if (homeFigure.isCygnus) cygnusTargetRoll * focus else 0f
            val constellationPoints = homeConstellationPoints(homeFigure.geometry, orbitPhase).map { point ->
                val p = transform(world, Vec3(
                    orbitCenter.x * .88f + (point.x - orbitCenter.x) * .66f,
                    orbitCenter.y * .88f + (point.y - orbitCenter.y) * .66f,
                    orbitCenter.z * .88f + (point.z - orbitCenter.z) * .66f,
                ))
                if (isFocus) {
                    // Approach is a world-space translation, not a uniform screen-space scale.
                    val approached = Vec3(p.x, p.y, p.z + 1.05f * focusProgress)
                    if (homeFigure.isCygnus) rollAroundCenter(approached, center, cygnusRoll) else approached
                } else p
            }
            drawConstellationCloud(index, orbitCenter, center, cygnusRoll,
                alpha * lineVisibility * (.60f + .40f * focus) * .30f, focus, DustLayer.FILAMENT, dustTargets)
            drawConstellationCloud(index, orbitCenter, center, cygnusRoll,
                alpha * starVisibility * .86f, focus, DustLayer.FLECK, dustTargets)

            points.clear()
            constellationPoints.forEachIndexed { starIndex, point ->
                val style = homeFigure.stars[starIndex]
                putVec(points, point)
                points.put(style.intensity)
                points.put(style.scale)
                val screen = project(point)
                hitStars.add(HomeStarHit(index, starIndex, screen.x, screen.y, screen.z,
                    CelestialGeometry.isOccluded(point, camera)))
            }
            points.flip()
            drawPoints(points, constellationPoints.size, viewProjection,
                density * 32f, alpha * starVisibility, true)
        }
        projectedStars = hitStars
        projectedDust = dustTargets
    }

    /** A shallow volume of starlight, not a camera-facing illustration or a new wire outline. */
    private fun drawConstellationCloud(figureIndex: Int, orbitCenter: Vec3, center: Vec3, roll: Float,
        alpha: Float, focus: Float, layer: DustLayer, dustTargets: MutableList<HomeStarHit>) {
        val homeFigure = snapshot.figures[figureIndex]
        val figure = homeFigure.geometry
        val angle = orbitPhase + figure.phaseOffset
        val motes = when (layer) {
            DustLayer.FILAMENT -> homeFigure.filaments
            DustLayer.FLECK -> homeFigure.flecks
        }
        val buffer = when (layer) {
            DustLayer.FILAMENT -> filaments
            DustLayer.FLECK -> flecks
        }
        val program = when (layer) {
            DustLayer.FILAMENT -> filamentProgram
            DustLayer.FLECK -> fleckProgram
        }
        val isFilament = layer == DustLayer.FILAMENT
        val isFleck = layer == DustLayer.FLECK
        buffer.clear()
        motes.forEachIndexed { index, mote ->
            val orbital = CelestialGeometry.rotateX(
                CelestialGeometry.rotateY(Vec3(mote.position.x, mote.position.y,
                    CelestialGeometry.ORBIT_RADIUS + mote.position.z), angle),
                figure.inclination,
            )
            val p = transform(world, Vec3(
                orbitCenter.x * .88f + (orbital.x - orbitCenter.x) * .66f,
                orbitCenter.y * .88f + (orbital.y - orbitCenter.y) * .66f,
                orbitCenter.z * .88f + (orbital.z - orbitCenter.z) * .66f,
            ))
            val point = rollAroundCenter(Vec3(p.x, p.y, p.z + 1.05f * focus), center, roll)
            putVec(buffer, point)
            buffer.put(mote.intensity)
            buffer.put(mote.scale)
            if (!isFleck && index % 4 == 0 && mote.intensity * alpha > if (isFilament) .025f else .08f) {
                val screen = project(point)
                dustTargets.add(HomeStarHit(figureIndex, -1, screen.x, screen.y, screen.z,
                    CelestialGeometry.isOccluded(point, camera)))
            }
        }
        buffer.flip()
        program.use()
        program.matrix("uMvp", viewProjection)
        program.f1("uSize", density * if (isFleck) 26f else if (isFilament) 25f else 28f)
        program.f1("uAlpha", alpha)
        program.f1("uTime", elapsed)
        program.f3("uColor", if (isFleck) .96f else if (isFilament) .94f else .88f,
            .86f, if (isFleck) .72f else if (isFilament) .83f else 1f)
        buffer.position(0)
        program.attribute("aPosition", 3, 20, buffer)
        buffer.position(3)
        program.attribute("aIntensity", 1, 20, buffer)
        buffer.position(4)
        program.attribute("aScale", 1, 20, buffer)
        glDrawArrays(GL_POINTS, 0, motes.size)
        program.disableAttributes()
    }

    /** Only a close-up pose adjustment; the volume keeps its real depth and rigid proportions. */
    private fun rollAroundCenter(point: Vec3, center: Vec3, angle: Float): Vec3 {
        val dx = point.x - center.x
        val dy = point.y - center.y
        return Vec3(center.x + dx * cos(angle) - dy * sin(angle),
            center.y + dx * sin(angle) + dy * cos(angle), point.z)
    }

    private fun drawPoints(buffer: FloatBuffer, count: Int, matrix: FloatArray, size: Float,
        alpha: Float, warm: Boolean) {
        val program = if (warm) anchorProgram else starProgram
        program.use()
        program.matrix("uMvp", matrix)
        program.f1("uSize", size)
        program.f1("uAlpha", alpha)
        program.f1("uTime", elapsed)
        program.f3("uColor", if (warm) 1f else .72f, if (warm) .84f else .79f, if (warm) .63f else .94f)
        buffer.position(0)
        program.attribute("aPosition", 3, 20, buffer)
        buffer.position(3)
        program.attribute("aIntensity", 1, 20, buffer)
        buffer.position(4)
        program.attribute("aScale", 1, 20, buffer)
        glDrawArrays(GL_POINTS, 0, count)
        program.disableAttributes()
        buffer.position(0)
    }

    private fun project(point: Vec3): Vec3 {
        val input = floatArrayOf(point.x, point.y, point.z, 1f)
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, viewProjection, 0, input, 0)
        return Vec3((clip[0] / clip[3] * .5f + .5f) * width, (.5f - clip[1] / clip[3] * .5f) * height, clip[2] / clip[3])
    }

    private fun loadTexture(): Int {
        val decoded = requireNotNull(BitmapFactory.decodeResource(resources, R.drawable.home_planet))
        // Power-of-two storage allows repeat wrapping and mipmapping on ES 2 GPUs.
        val bitmap = Bitmap.createScaledBitmap(decoded, 2048, 1024, true)
        val id = IntArray(1)
        glGenTextures(1, id, 0)
        glBindTexture(GL_TEXTURE_2D, id[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
        glGenerateMipmap(GL_TEXTURE_2D)
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        return id[0]
    }

    private fun makeStarfield(): FloatBuffer {
        val random = Random(20260914)
        return floats(FloatArray(620 * 5).also { data ->
            repeat(620) { index ->
                data[index * 5] = (random.nextFloat() - .5f) * 17f
                data[index * 5 + 1] = (random.nextFloat() - .5f) * 26f
                data[index * 5 + 2] = -6f - random.nextFloat() * 7f
                data[index * 5 + 3] = .18f + random.nextFloat() * .68f
                data[index * 5 + 4] = .8f + random.nextFloat() * 2f
            }
        })
    }

    private enum class DustLayer { FILAMENT, FLECK }

    private companion object { const val TAG = "CelestialScene" }
}

private class SphereMesh(longitudes: Int, latitudes: Int) {
    private val vertices: FloatBuffer
    private val indices: ShortBuffer
    private val count = longitudes * latitudes * 6
    init {
        val data = FloatArray((longitudes + 1) * (latitudes + 1) * 5)
        var offset = 0
        for (y in 0..latitudes) {
            val latitude = PI.toFloat() * y / latitudes
            for (x in 0..longitudes) {
                val longitude = PI.toFloat() * 2f * x / longitudes
                data[offset++] = sin(latitude) * sin(longitude)
                data[offset++] = cos(latitude)
                data[offset++] = sin(latitude) * cos(longitude)
                data[offset++] = x.toFloat() / longitudes
                data[offset++] = y.toFloat() / latitudes
            }
        }
        vertices = floats(data)
        val triangles = ShortArray(count)
        var index = 0
        for (y in 0 until latitudes) for (x in 0 until longitudes) {
            val a = y * (longitudes + 1) + x
            val b = a + longitudes + 1
            triangles[index++] = a.toShort()
            triangles[index++] = b.toShort()
            triangles[index++] = (a + 1).toShort()
            triangles[index++] = (a + 1).toShort()
            triangles[index++] = b.toShort()
            triangles[index++] = (b + 1).toShort()
        }
        indices = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(triangles); position(0) }
    }

    fun draw(program: Program) {
        vertices.position(0)
        program.attribute("aPosition", 3, 20, vertices)
        vertices.position(3)
        program.attribute("aUv", 2, 20, vertices)
        indices.position(0)
        glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_SHORT, indices)
        program.disableAttributes()
        vertices.position(0)
    }
}

private class Program(vertex: String, fragment: String) {
    private val id: Int
    private val uniforms = HashMap<String, Int>()
    private val attributes = HashMap<String, Int>()
    private val activeAttributes = ArrayList<Int>(4)
    init {
        val vs = compile(GL_VERTEX_SHADER, vertex)
        val fs = compile(GL_FRAGMENT_SHADER, fragment)
        id = glCreateProgram()
        glAttachShader(id, vs)
        glAttachShader(id, fs)
        glLinkProgram(id)
        val success = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, success, 0)
        check(success[0] != 0) { glGetProgramInfoLog(id) }
        glDeleteShader(vs)
        glDeleteShader(fs)
    }
    fun use() = glUseProgram(id)
    fun uniform(name: String): Int = uniforms.getOrPut(name) { glGetUniformLocation(id, name) }
    fun f1(name: String, x: Float) = glUniform1f(uniform(name), x)
    fun f2(name: String, x: Float, y: Float) = glUniform2f(uniform(name), x, y)
    fun f3(name: String, x: Float, y: Float, z: Float) = glUniform3f(uniform(name), x, y, z)
    fun f4(name: String, x: Float, y: Float, z: Float, w: Float) = glUniform4f(uniform(name), x, y, z, w)
    fun matrix(name: String, matrix: FloatArray) = glUniformMatrix4fv(uniform(name), 1, false, matrix, 0)
    fun attribute(name: String, size: Int, stride: Int, buffer: FloatBuffer) {
        val location = attributes.getOrPut(name) { glGetAttribLocation(id, name) }
        if (location < 0) return
        glEnableVertexAttribArray(location)
        glVertexAttribPointer(location, size, GL_FLOAT, false, stride, buffer)
        activeAttributes.add(location)
    }
    fun disableAttributes() { activeAttributes.forEach { glDisableVertexAttribArray(it) }; activeAttributes.clear() }
    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        val success = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, success, 0)
        check(success[0] != 0) { glGetShaderInfoLog(shader) }
        return shader
    }
}

private fun floats(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
    .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
private fun putVec(buffer: FloatBuffer, value: Vec3) { buffer.put(value.x); buffer.put(value.y); buffer.put(value.z) }
private fun transform(matrix: FloatArray, v: Vec3): Vec3 = Vec3(
    matrix[0] * v.x + matrix[4] * v.y + matrix[8] * v.z + matrix[12],
    matrix[1] * v.x + matrix[5] * v.y + matrix[9] * v.z + matrix[13],
    matrix[2] * v.x + matrix[6] * v.y + matrix[10] * v.z + matrix[14],
)
private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z
private fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
private fun length(p: Vec3) = sqrt(dot(p, p))
private fun normalize(p: Vec3): Vec3 { val n = length(p).coerceAtLeast(.00001f); return Vec3(p.x / n, p.y / n, p.z / n) }

private const val SPHERE_VERTEX = """
attribute vec3 aPosition;
attribute vec2 aUv;
uniform mat4 uModel;
uniform mat4 uMvp;
varying vec3 vPosition;
varying vec3 vNormal;
varying vec3 vTangent;
varying vec2 vUv;
void main() {
    vPosition = (uModel * vec4(aPosition, 1.0)).xyz;
    vNormal = normalize(mat3(uModel) * aPosition);
    vTangent = normalize(mat3(uModel) * vec3(aPosition.z, 0.001, -aPosition.x));
    vUv = aUv;
    gl_Position = uMvp * vec4(aPosition, 1.0);
}
"""

private const val PLANET_FRAGMENT = """
precision highp float;
uniform sampler2D uTexture;
uniform vec3 uCamera;
uniform float uFocus;
varying vec3 vPosition;
varying vec3 vNormal;
varying vec3 vTangent;
varying vec2 vUv;
float heightAt(vec2 uv) { return dot(texture2D(uTexture, uv).rgb, vec3(0.299, 0.587, 0.114)); }
void main() {
    vec3 normal = normalize(vNormal);
    vec3 tangent = normalize(vTangent);
    vec3 bitangent = normalize(cross(normal, tangent));
    vec2 texel = vec2(1.0 / 2048.0, 1.0 / 1024.0);
    float dx = heightAt(vUv + vec2(texel.x,0.0)) - heightAt(vUv - vec2(texel.x,0.0));
    float dy = heightAt(vUv + vec2(0.0,texel.y)) - heightAt(vUv - vec2(0.0,texel.y));
    vec3 bumped = normalize(normal - tangent * dx * 2.2 + bitangent * dy * 2.2);
    vec3 light = normalize(vec3(0.94, 0.62, 0.74));
    vec3 viewDir = normalize(uCamera - vPosition);
    float diffuse = max(dot(bumped, light), 0.0);
    float terminator = smoothstep(-0.08, 0.19, dot(normal, light));
    vec3 albedo = pow(texture2D(uTexture, vUv).rgb, vec3(1.14));
    albedo = mix(albedo * vec3(0.68,0.67,0.93), albedo * vec3(1.02,0.97,0.92), diffuse);
    vec3 ambient = vec3(0.052,0.057,0.105);
    vec3 color = albedo * (ambient + vec3(1.06,1.0,0.93) * diffuse * terminator * 1.20);
    float rim = pow(1.0 - max(dot(normal, viewDir), 0.0), 4.0);
    color += vec3(0.095,0.17,0.36) * rim * (0.17 + 0.48 * terminator);
    color *= 1.0 - uFocus * 0.77;
    gl_FragColor = vec4(color, 1.0);
}
"""

private const val ATMOSPHERE_FRAGMENT = """
precision mediump float;
uniform vec3 uCamera;
uniform float uFocus;
varying vec3 vPosition;
varying vec3 vNormal;
void main() {
    vec3 n = normalize(vNormal);
    vec3 eye = normalize(uCamera - vPosition);
    float rim = pow(1.0 - abs(dot(n, eye)), 5.5);
    float light = max(dot(n, normalize(vec3(0.9,0.62,0.74))), 0.0);
    float alpha = rim * (0.07 + light * 0.20) * (1.0 - uFocus * 0.85);
    gl_FragColor = vec4(mix(vec3(0.26,0.39,0.72), vec3(0.70,0.64,0.79),light), alpha);
}
"""

private const val STAR_VERTEX = """
attribute vec3 aPosition;
attribute float aIntensity;
attribute float aScale;
uniform mat4 uMvp;
uniform float uSize;
uniform float uTime;
varying float vIntensity;
void main() {
    gl_Position = uMvp * vec4(aPosition, 1.0);
    gl_PointSize = clamp(uSize * aScale * 6.4 / gl_Position.w, 2.0, 120.0);
    vIntensity = aIntensity * (0.91 + 0.09 * sin(uTime * 1.1 + aPosition.x * 8.0 + aPosition.y * 4.0));
}
"""
private const val STAR_FRAGMENT = """
precision mediump float;
uniform vec3 uColor;
uniform float uAlpha;
varying float vIntensity;
void main() {
    vec2 p = gl_PointCoord - 0.5;
    float r2 = dot(p,p);
    float core = exp(-r2 * 820.0);
    float halo = exp(-r2 * 26.0) * 0.085;
    float rays = (exp(-abs(p.x)*180.0 - abs(p.y)*16.0) + exp(-abs(p.y)*180.0 - abs(p.x)*20.0)) * 0.22;
    float alpha = (core + halo + rays) * vIntensity * uAlpha;
    if (alpha < 0.003) discard;
    gl_FragColor = vec4(mix(uColor, vec3(1.0,0.97,0.87),core), alpha);
}
"""
private const val CYGNUS_DUST_FRAGMENT = """
precision mediump float;
uniform vec3 uColor;
uniform float uAlpha;
varying float vIntensity;
void main() {
    vec2 p = gl_PointCoord - 0.5;
    float r2 = dot(p, p);
    float grain = exp(-r2 * 360.0) * 0.68;
    float veil = exp(-r2 * 26.0) * 0.14;
    float alpha = (grain + veil) * vIntensity * uAlpha;
    if (alpha < 0.002) discard;
    gl_FragColor = vec4(mix(uColor, vec3(1.0, 0.94, 0.81), grain), alpha);
}
"""
private const val FILAMENT_FRAGMENT = """
precision mediump float;
uniform vec3 uColor;
uniform float uAlpha;
varying float vIntensity;
void main() {
    vec2 p = gl_PointCoord - 0.5;
    float r2 = dot(p, p);
    // No bright pinprick: only the named anchor stars have distinct luminous cores.
    float lightDust = exp(-r2 * 180.0) * 0.25;
    float mist = exp(-r2 * 25.0) * 0.21;
    float alpha = (lightDust + mist) * vIntensity * uAlpha;
    if (alpha < 0.002) discard;
    gl_FragColor = vec4(uColor, alpha);
}
"""
private const val ANCHOR_FRAGMENT = """
precision mediump float;
uniform vec3 uColor;
uniform float uAlpha;
varying float vIntensity;
void main() {
    vec2 p = gl_PointCoord - 0.5;
    float r2 = dot(p, p);
    float core = exp(-r2 * 650.0);
    float innerGlow = exp(-r2 * 145.0) * 0.27;
    float halo = exp(-r2 * 32.0) * 0.04;
    float rays = (exp(-abs(p.x) * 235.0 - abs(p.y) * 25.0)
        + exp(-abs(p.y) * 235.0 - abs(p.x) * 29.0)) * 0.14;
    float alpha = (core + innerGlow + halo + rays) * vIntensity * uAlpha;
    if (alpha < 0.002) discard;
    gl_FragColor = vec4(mix(uColor, vec3(1.0, 0.985, 0.94), core), alpha);
}
"""
private const val FLECK_FRAGMENT = """
precision mediump float;
uniform vec3 uColor;
uniform float uAlpha;
varying float vIntensity;
void main() {
    vec2 p = gl_PointCoord - 0.5;
    float r2 = dot(p, p);
    // Sparse little pinpricks beside the mist, without the named stars' large rays.
    float core = exp(-r2 * 460.0);
    float glow = exp(-r2 * 78.0) * 0.13;
    float alpha = (core + glow) * vIntensity * uAlpha;
    if (alpha < 0.002) discard;
    gl_FragColor = vec4(mix(uColor, vec3(1.0, 0.97, 0.88), core), alpha);
}
"""
private const val BACKGROUND_VERTEX = """
attribute vec2 aPosition;
varying vec2 vUv;
void main() { vUv = aPosition * 0.5 + 0.5; gl_Position = vec4(aPosition, 0.99, 1.0); }
"""
private const val BACKGROUND_FRAGMENT = """
precision mediump float;
varying vec2 vUv;
uniform float uAspect;
uniform float uFocus;
uniform float uTime;
uniform vec2 uDrift;
float hash(vec2 p) { return fract(sin(dot(p,vec2(127.1,311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i=floor(p); vec2 f=fract(p); f=f*f*(3.0-2.0*f);
    return mix(mix(hash(i),hash(i+vec2(1.0,0.0)),f.x),mix(hash(i+vec2(0.0,1.0)),hash(i+vec2(1.0)),f.x),f.y);
}
void main() {
    vec2 uv = vUv + uDrift;
    vec2 p = (uv - 0.5) * vec2(uAspect,1.0);
    float cloud = noise(p*6.0+2.0) * 0.5 + noise(p*15.0) * 0.25 + noise(p*40.0) * 0.125;
    float band = exp(-pow((p.x + p.y * 0.32 + 0.03) * 6.0,2.0));
    vec3 color = vec3(0.010,0.020,0.040);
    color += vec3(0.035,0.040,0.065) * cloud * band;
    color += vec3(0.014,0.011,0.025) * exp(-length(p-vec2(.2,.18))*4.0);
    color += (hash(gl_FragCoord.xy) - 0.5) * 0.006;
    color *= (0.8 + 0.2 * (1.0 - length(p))) * (1.0-uFocus*.45);
    gl_FragColor = vec4(color,1.0);
}
"""
