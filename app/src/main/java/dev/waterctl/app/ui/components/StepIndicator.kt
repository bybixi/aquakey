package dev.waterctl.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/** 「连接中」三步进度的三种状态。 */
enum class StepState { DONE, IN_PROGRESS, PENDING }

/**
 * 步骤指示器 —— 设计稿里对应「蓝牙已连接 / 正在校验密钥 / 启动会话」三个圆点。
 * 完成是实心圆 + 勾，进行中是环 + 圆弧，待执行是空心环。
 */
@Composable
fun StepIndicator(state: StepState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier = modifier) {
        val w = size.minDimension
        val stroke = w * 0.12f
        val radius = (w - stroke) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        when (state) {
            StepState.DONE -> {
                drawCircle(color = scheme.primary, radius = w / 2f)
                val check = Path().apply {
                    moveTo(w * 0.28f, w * 0.52f)
                    lineTo(w * 0.44f, w * 0.68f)
                    lineTo(w * 0.74f, w * 0.34f)
                }
                drawPath(
                    path = check,
                    color = scheme.onPrimary,
                    style = Stroke(
                        width = w * 0.11f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }

            StepState.IN_PROGRESS -> {
                drawCircle(
                    color = scheme.outlineVariant,
                    radius = radius,
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = scheme.primary,
                    startAngle = -90f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            StepState.PENDING -> {
                drawCircle(
                    color = scheme.outlineVariant,
                    radius = radius,
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}
