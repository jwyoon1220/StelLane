package io.github.jwyoon1220.engine.pool

/**
 * 제네릭 오브젝트 풀.
 * acquire()로 객체를 얻고, release()로 반환합니다.
 * 단일 스레드(GameLoopThread)에서만 사용하므로 동기화 없음.
 */
class ObjectPool<T>(
    private val factory: () -> T,
    private val reset: (T) -> Unit
) {
    private val pool = ArrayDeque<T>()

    fun acquire(): T = pool.removeLastOrNull()?.also(reset) ?: factory()

    fun release(obj: T) {
        pool.addLast(obj)
    }

    fun releaseAll(iterable: Iterable<T>) {
        iterable.forEach { pool.addLast(it) }
    }

    val poolSize: Int get() = pool.size
}
