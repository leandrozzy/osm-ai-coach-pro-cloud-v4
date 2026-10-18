package com.osmaicoach.collector

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

/** Arte 3D renderizada (Blender) empacotada em res/drawable-nodpi com o prefixo osm_. */
private val ART = mapOf("bg_stadium" to R.drawable.osm_bg_stadium)

@Composable
fun osmArt(name: String): Painter = painterResource(ART[name] ?: R.drawable.osm_bg_stadium)

/**
 * Arte animada (WebP animado em res/drawable-nodpi com o prefixo osm_anim_): a seta se desenha, a bandeira sobe.
 * Toca uma vez sempre que aparece ou que a opção muda e fica no último quadro. Sem animação, mostra a imagem fixa.
 */
@Composable
fun OsmAnim(name: String, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val id = remember(name) { ctx.resources.getIdentifier("osm_anim_$name", "drawable", ctx.packageName) }
    if (id == 0) {
        androidx.compose.foundation.Image(osmArt(name), null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        return
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c -> android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
        update = { v ->
            if (v.tag != name) {
                v.tag = name
                val d = try {
                    android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(v.resources, id))
                } catch (e: Exception) {
                    null
                }
                v.setImageDrawable(d)
                (d as? android.graphics.drawable.AnimatedImageDrawable)?.apply {
                    repeatCount = 0
                    start()
                }
            }
        },
        modifier = modifier
    )
}
