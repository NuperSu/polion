package io.polion.ui.model

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ModelCoinIdentity(
    val ticker: String,
    val name: String,
    val color: Color,
    val mark: String,
    val id: String = ticker,
)

private val White = Color(0xFFF5F5F5)

@Composable
fun ModelCoinLogo(
    coin: ModelCoinIdentity,
    size: Int = 32,
) {
    Box(Modifier.size(size.dp).background(coin.color, CircleShape), contentAlignment = Alignment.Center) {
        when (coin.ticker) {
            "ETH" ->
                Canvas(Modifier.size((size * .72f).dp)) {
                    val x = this.size.width / 2
                    val h = this.size.height
                    drawPath(
                        Path().apply {
                            moveTo(x, 0f)
                            lineTo(x * 1.7f, h * .55f)
                            lineTo(x, h * .72f)
                            lineTo(x * .3f, h * .55f)
                            close()
                        },
                        Color(0xFFDCE5FF),
                    )
                    drawPath(
                        Path().apply {
                            moveTo(x, h)
                            lineTo(x * 1.7f, h * .62f)
                            lineTo(x, h * .8f)
                            lineTo(x * .3f, h * .62f)
                            close()
                        },
                        Color(0xFFB1BFFF),
                    )
                    drawPath(
                        Path().apply {
                            moveTo(x, 0f)
                            lineTo(x, h * .72f)
                            lineTo(x * .3f, h * .55f)
                            close()
                        },
                        Color.White,
                    )
                }
            "SOL" ->
                Canvas(Modifier.size((size * .65f).dp)) {
                    repeat(3) { n ->
                        val y =
                            this.size.height * (.14f + n * .3f)
                        drawLine(
                            Color.White,
                            Offset(4f, y),
                            Offset(this.size.width - 4, y),
                            this.size.height * .15f,
                        )
                    }
                }
            "XRP" ->
                Canvas(Modifier.size((size * .65f).dp)) {
                    val w = this.size.width
                    val h = this.size.height
                    drawPath(
                        Path().apply {
                            moveTo(0f, 0f)
                            cubicTo(w * .4f, h * .6f, w * .6f, h * .6f, w, 0f)
                        },
                        White,
                        style = Stroke(2.dp.toPx()),
                    )
                    drawPath(
                        Path().apply {
                            moveTo(0f, h)
                            cubicTo(w * .4f, h * .4f, w * .6f, h * .4f, w, h)
                        },
                        White,
                        style = Stroke(2.dp.toPx()),
                    )
                }
            "BNB" ->
                Canvas(Modifier.size((size * .58f).dp)) {
                    drawPath(
                        Path().apply {
                            moveTo(this@Canvas.size.width / 2, 0f)
                            lineTo(
                                this@Canvas.size.width,
                                this@Canvas.size.height / 2,
                            )
                            lineTo(this@Canvas.size.width / 2, this@Canvas.size.height)
                            lineTo(
                                0f,
                                this@Canvas.size.height / 2,
                            )
                            close()
                        },
                        White,
                        style = Stroke(3.dp.toPx()),
                    )
                }
            else -> Text(coin.mark, color = White, fontSize = (size * .72f).sp, fontWeight = FontWeight.Bold)
        }
    }
}
