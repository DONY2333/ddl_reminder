package com.example.ddlreminder.diagnostics

import android.content.Context
import android.util.Log
import com.example.ddlreminder.BuildConfig
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** Debug-only 的有界本地面包屑。只接收固定节点和类名，不接收业务内容或异常 message。 */
object StabilityDiagnostics {
    enum class Node { APP_STARTED, INITIALIZATION_FAILED, ROUTE, UI_STATE, CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED, RECOVERABLE_FAILURE, UNCAUGHT }
    private var prefs: android.content.SharedPreferences? = null
    private val recent = ArrayDeque<String>()
    private var previousRoute = "none"
    private var handlerInstalled = false
    private val routes = setOf("calendar", "smart_add", "settings", "editor/{ddlId}/{date}", "recovery")
    private val states = setOf("Idle", "Parsing", "Preview", "ParseFailed", "Creating", "CreateSucceeded")

    fun initialize(context: Context) {
        if (!BuildConfig.DEBUG) return
        prefs = context.getSharedPreferences("stability_diagnostics", Context.MODE_PRIVATE)
        prefs?.getString("recent", "")?.lineSequence()?.filter { it.isNotBlank() }?.take(60)?.forEach { recent.addLast(it) }
        if (!handlerInstalled) {
            handlerInstalled = true
            val original = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                record(Node.UNCAUGHT, error = error)
                // 不吞不可恢复异常；默认 handler 仍会结束异常进程。
                original?.uncaughtException(thread, sanitizedCrash(error))
            }
        }
        record(Node.APP_STARTED)
    }

    /** 保留真正异常类名和源码帧，但不让系统默认 fatal 日志输出可能含正文的 message/cause。 */
    fun sanitizedCrash(error: Throwable): RuntimeException =
        RuntimeException("Uncaught " + error.javaClass.simpleName).apply { stackTrace = error.stackTrace }

    @Synchronized
    fun record(node: Node, route: String? = null, state: String? = null, error: Throwable? = null) {
        // 无 Android Context 的纯 JVM 测试不触碰 mockable android.util.Log。
        if (!BuildConfig.DEBUG || prefs == null) return
        val safeRoute = route?.takeIf { it in routes } ?: "unknown"
        val line = buildString {
            append(Instant.now()); append(" node="); append(node.name)
            if (route != null) {
                append(" previousRoute="); append(previousRoute)
                append(" route="); append(safeRoute)
                previousRoute = safeRoute
            }
            if (state != null) { append(" state="); append(state.takeIf { it in states } ?: "unknown") }
            if (error != null) { append(" exception="); append(error.javaClass.simpleName) }
        }
        recent.addLast(line)
        while (recent.size > 80) recent.removeFirst()
        // apply 的磁盘写入异步执行，避免在主线程同步写文件。
        try { prefs?.edit()?.putString("recent", recent.joinToString("\n"))?.apply() }
        catch (failure: Exception) { Log.e("DDLStability", "DiagnosticStorageFailure=" + failure.javaClass.simpleName) }
        Log.i("DDLStability", line)
        // 仅帧，不输出 Throwable.toString/message，也不输出带用户数据的 cause message。
        error?.stackTrace?.take(16)?.forEach { Log.e("DDLStability", "at $it") }
    }
}

/** 与 runCatching 不同：不吞 CancellationException，也不捕获 Error。 */
inline fun <T> recoverable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Exception) {
    if (error is CancellationException) throw error
    StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = error)
    Result.failure(error)
}
