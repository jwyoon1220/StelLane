package io.github.jwyoon1220.engine

import sun.misc.Unsafe

/**
 * 초당 수만~수십만 번 불리는 오프힙 정점 쓰기 경로 전용 — `sun.misc.Unsafe`로 직접 씁니다.
 *
 * JDK 21 기준 `ByteBuffer.putFloat`/`FloatBuffer.put(int,float)`뿐 아니라 LWJGL 3.3.4의
 * `MemoryUtil.memPutFloat`까지도 내부적으로 `jdk.internal.misc.ScopedMemoryAccess`(메모리 세션
 * 라이브니스 체크)를 거칩니다 — 호출당 비용은 작아 보여도 [Vulkan2DBatcher.pushVertex]/
 * [GlQuadBatchRenderer.putVertexF]처럼 프레임당 수천 번씩 불리는 경로에서는 프로파일링 결과
 * 메인 스레드 CPU 샘플의 절반 이상을 차지할 만큼 누적 비용이 컸습니다.
 *
 * `Unsafe.putFloat(long, float)`는 이 라이브니스 체크를 건너뛰고 포인터에 직접 씁니다 — 대신
 * 버퍼가 아직 살아있음을 호출자가 직접 보장해야 합니다(우리 정점 버퍼는 frame-in-flight 펜스로
 * GPU 사용이 끝난 뒤에만 재사용/해제되므로 이 보장이 이미 성립합니다).
 */
object UnsafeMemory {
    val UNSAFE: Unsafe = run {
        val f = Unsafe::class.java.getDeclaredField("theUnsafe")
        f.isAccessible = true
        f.get(null) as Unsafe
    }

    /** `Unsafe.copyMemory(int[], ...)` 소스 오프셋 — IntArray 벌크 memcpy에 씀([VulkanVideoTexture] 참고). */
    val ARRAY_INT_BASE_OFFSET: Long = UNSAFE.arrayBaseOffset(IntArray::class.java).toLong()
}
