package io.github.jwyoon1220.engine.data.pool

/**
 * 제네릭 오브젝트 풀.
 * acquire()로 객체를 얻고, release()로 반환합니다.
 * 단일 스레드(GameLoopThread)에서만 사용하므로 동기화 없음.
 */
class ObjectPool<T : Any>(
    initialCapacity: Int = 128,
    private val factory: () -> T,
    private val reset: (T) -> Unit
) {
    private var pool: Array<Any?> = arrayOfNulls(initialCapacity)
    private var head = 0

    /** 객체를 가져옵니다. 풀이 비어있으면 새로 생성합니다. */
    @Suppress("UNCHECKED_CAST")
    fun acquire(): T {
        return if (head > 0) {
            val obj = pool[--head] as T
            pool[head] = null // 메모리 누수 방지 (참조 해제)
            reset(obj)
            obj
        } else {
            val obj = factory()
            reset(obj)
            obj
        }
    }

    /** 객체를 풀에 반환합니다. */
    fun release(obj: T) {
        if (head == pool.size) {
            // 풀 가득 참 -> 2배로 확장
            val newPool = arrayOfNulls<Any?>(pool.size * 2)
            System.arraycopy(pool, 0, newPool, 0, pool.size)
            pool = newPool
        }
        pool[head++] = obj
    }

    /** 리스트에 있는 모든 객체를 반환합니다. Iterator 할당을 막기 위해 인덱스 루프를 사용합니다. */
    fun releaseAll(list: List<T>) {
        for (i in list.indices) {
            release(list[i])
        }
    }

    /** 현재 풀에 남아있는 (반환된) 객체 수 */
    val poolSize: Int get() = head

    /** 게임 시작 전 미리 객체를 생성해두어 플레이 중 프레임 드랍을 막습니다. */
    fun preAllocate(count: Int) {
        for (i in 0 until count) {
            release(factory())
        }
    }
}
