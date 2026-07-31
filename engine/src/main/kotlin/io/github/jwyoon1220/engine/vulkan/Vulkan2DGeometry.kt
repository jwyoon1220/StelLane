package io.github.jwyoon1220.engine.vulkan

import it.unimi.dsi.fastutil.floats.FloatArrayList
import kotlin.math.cos
import kotlin.math.sin

/** 둥근 사각형/타원 둘레 점 목록을 만드는 순수 수학 헬퍼 — [Vulkan2DBatcher.pushFan]에 넘길 용도. */
object Vulkan2DGeometry {
    private const val SEGMENTS_PER_CORNER = 12 // 모서리 하나(90도)당 세그먼트 수
    private const val ELLIPSE_SEGMENTS = 32

    private const val HALF_PI = (Math.PI / 2.0).toFloat()
    private const val PI_F = Math.PI.toFloat()
    private const val TWO_PI = (Math.PI * 2.0).toFloat()

    // fillCircle/fillRoundRect가 매 프레임 수십~수백 번 불리는 경로라(별빛 반짝임, 둥근 UI 패널 등),
    // 매 호출마다 sin/cos를 다시 계산하지 않도록 단위원 좌표를 한 번만 구해둡니다.
    // roundedRectPoints의 네 모서리는 이 사분원 하나를 90도씩 회전(부호 교환)해서 재사용합니다 —
    // cos(t±90°)/sin(t±90°)를 회전 공식으로 구하면 삼각함수 호출 없이 순수 덧셈/부호변경만 남습니다.
    private val quarterCos = FloatArray(SEGMENTS_PER_CORNER + 1) { i -> cos(HALF_PI * (i.toFloat() / SEGMENTS_PER_CORNER)) }
    private val quarterSin = FloatArray(SEGMENTS_PER_CORNER + 1) { i -> sin(HALF_PI * (i.toFloat() / SEGMENTS_PER_CORNER)) }
    private val ellipseCos = FloatArray(ELLIPSE_SEGMENTS) { i -> cos(TWO_PI * (i.toFloat() / ELLIPSE_SEGMENTS)) }
    private val ellipseSin = FloatArray(ELLIPSE_SEGMENTS) { i -> sin(TWO_PI * (i.toFloat() / ELLIPSE_SEGMENTS)) }

    /**
     * (x,y,w,h) 사각형의 네 모서리를 반경 [radius]로 둥글린 둘레 점을 시계 방향으로 반환합니다
     * (화면 좌표계는 y가 아래로 증가 — 우상단→우하단→좌하단→좌상단 순서).
     */
    fun roundedRectPoints(x: Float, y: Float, w: Float, h: Float, radius: Float): FloatArray =
        roundedRectPoints(x, y, w, h, radius, radius, radius, radius)

    /**
     * 모서리별로 다른 반경을 줄 수 있는 버전 — `nvgRoundedRectVarying`과 동일한 용도
     * (예: 위쪽 모서리만 둥글고 아래는 각진 카드). 반경이 0.01 이하인 모서리는 호(arc) 없이
     * 뾰족한 점 하나만 넣습니다.
     */
    fun roundedRectPoints(x: Float, y: Float, w: Float, h: Float, rTL: Float, rTR: Float, rBR: Float, rBL: Float): FloatArray {
        val maxR = minOf(w, h) / 2f
        val cTL = rTL.coerceIn(0f, maxR); val cTR = rTR.coerceIn(0f, maxR)
        val cBR = rBR.coerceIn(0f, maxR); val cBL = rBL.coerceIn(0f, maxR)

        // ArrayList<Float>는 점 하나당 박싱 할당 2번(x,y)씩 생겨 라운드rect가 자주 그려지는 UI에서
        // GC 압박이 컸습니다 — fastutil의 원시 float 리스트로 박싱을 없앱니다.
        val points = FloatArrayList((SEGMENTS_PER_CORNER + 1) * 4 * 2)
        // 회전별 (cos(t+phase), sin(t+phase)) 부호/교환 공식 — quarterCos/Sin은 t=[0,HALF_PI] 구간.
        // BR(phase=0): (c, s) / BL(phase=+90°): (-s, c) / TL(phase=180°): (-c, -s) / TR(phase=-90°): (s, -c)
        fun arc(ccx: Float, ccy: Float, r: Float, corner: Int) {
            if (r <= 0.01f) {
                points.add(ccx); points.add(ccy) // 반경 0 — 뾰족한 코너 점 하나만(중심=코너 좌표와 일치)
                return
            }
            for (i in 0..SEGMENTS_PER_CORNER) {
                val c = quarterCos[i]; val s = quarterSin[i]
                val (dx, dy) = when (corner) {
                    0 -> s to -c   // TR: start=-90°
                    1 -> c to s    // BR: start=0°
                    2 -> -s to c   // BL: start=+90°
                    else -> -c to -s // TL: start=180°
                }
                points.add(ccx + dx * r)
                points.add(ccy + dy * r)
            }
        }
        // (모서리 중심, 코너 인덱스) — arc()가 인덱스에 맞는 회전 공식을 적용. r=0이면 중심 좌표가 곧 뾰족한 코너 좌표.
        arc(x + w - cTR, y + cTR,     cTR, 0) // 우상단: 위쪽(-90도)→오른쪽(0도)
        arc(x + w - cBR, y + h - cBR, cBR, 1) // 우하단: 오른쪽(0도)→아래쪽(90도)
        arc(x + cBL,     y + h - cBL, cBL, 2) // 좌하단: 아래쪽(90도)→왼쪽(180도)
        arc(x + cTL,     y + cTL,     cTL, 3) // 좌상단: 왼쪽(180도)→위쪽(270도)
        return points.toFloatArray()
    }

    /** 중심 (cx,cy), 반지름 (rx,ry)인 타원 둘레 점을 반환합니다. */
    fun ellipsePoints(cx: Float, cy: Float, rx: Float, ry: Float): FloatArray {
        val points = FloatArray(ELLIPSE_SEGMENTS * 2)
        for (i in 0 until ELLIPSE_SEGMENTS) {
            points[i * 2] = cx + ellipseCos[i] * rx
            points[i * 2 + 1] = cy + ellipseSin[i] * ry
        }
        return points
    }
}
