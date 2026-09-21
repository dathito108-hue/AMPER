package io.amper.neuroos

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class SafeLauncherActivity : Activity() {
    private val crashFile by lazy {
        File(filesDir, "amper-startup-crash.txt")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashCapture()
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is AmperCrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(
            AmperCrashHandler(crashFile, previous)
        )
    }

    private fun render() {
        val pad = (20f * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.TOP
        }

        root.addView(TextView(this).apply {
            text = "AMPER Safe Launcher"
            textSize = 24f
        })

        root.addView(TextView(this).apply {
            text =
                "Launcher tối giản đang hoạt động. Full AMPER chỉ khởi động khi bạn bấm nút bên dưới."
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        })

        if (intent.getBooleanExtra(EXTRA_PROACTIVE_ATTENTION, false)) {
            root.addView(TextView(this).apply {
                text =
                    "AMPER có một tác vụ chủ động cần bạn xem lại. Thông báo này không phê duyệt hoặc thực thi bất kỳ hành động nào."
                textSize = 16f
                setPadding(0, 0, 0, pad / 2)
            })
        }

        root.addView(Button(this).apply {
            text = "Mở full AMPER"
            setOnClickListener {
                startActivity(Intent(this@SafeLauncherActivity, MainActivity::class.java))
            }
        })

        val report = if (crashFile.exists()) {
            runCatching { crashFile.readText() }
                .getOrElse { "Không đọc được crash report: ${it.message}" }
        } else {
            "Chưa có crash report. Nếu full AMPER bị đóng, mở ứng dụng lại; lỗi sẽ hiện ở đây."
        }

        root.addView(TextView(this).apply {
            text = "Startup diagnostics"
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        })

        root.addView(TextView(this).apply {
            text = report.take(MAX_REPORT_CHARS)
            textSize = 12f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        root.addView(Button(this).apply {
            text = "Xóa crash report"
            isEnabled = crashFile.exists()
            setOnClickListener {
                runCatching { crashFile.delete() }
                render()
            }
        })

        setContentView(
            ScrollView(this).apply { addView(root) },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private class AmperCrashHandler(
        private val crashFile: File,
        private val delegate: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            runCatching {
                val trace = StringWriter().also {
                    throwable.printStackTrace(PrintWriter(it))
                }.toString()
                crashFile.writeText(
                    buildString {
                        appendLine("AMPER_STARTUP_CRASH_V1")
                        appendLine("thread=${thread.name}")
                        appendLine("type=${throwable::class.java.name}")
                        appendLine("message=${throwable.message ?: "~"}")
                        appendLine(trace)
                    }
                )
            }
            delegate?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val EXTRA_PROACTIVE_ATTENTION =
            "io.amper.neuroos.extra.PROACTIVE_ATTENTION"
        private const val MAX_REPORT_CHARS = 12_000
    }
}
