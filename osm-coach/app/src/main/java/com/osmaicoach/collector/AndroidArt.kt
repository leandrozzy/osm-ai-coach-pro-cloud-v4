package com.osmaicoach.collector

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

/** Arte 3D renderizada (Blender) empacotada em res/drawable-nodpi com o prefixo osm_. */
private val ART = mapOf(
    "bg_stadium" to R.drawable.osm_bg_stadium,
    "style_alas" to R.drawable.osm_style_alas, "style_passe" to R.drawable.osm_style_passe,
    "style_longa" to R.drawable.osm_style_longa, "style_contra" to R.drawable.osm_style_contra,
    "style_remate" to R.drawable.osm_style_remate,
    "tackle_cuidadoso" to R.drawable.osm_tackle_cuidadoso, "tackle_normal" to R.drawable.osm_tackle_normal,
    "tackle_agressivo" to R.drawable.osm_tackle_agressivo, "tackle_extremo" to R.drawable.osm_tackle_extremo,
    "sector_base" to R.drawable.osm_sector_base,
    "sector_att_0" to R.drawable.osm_sector_att_0, "sector_att_1" to R.drawable.osm_sector_att_1, "sector_att_2" to R.drawable.osm_sector_att_2,
    "sector_mid_0" to R.drawable.osm_sector_mid_0, "sector_mid_1" to R.drawable.osm_sector_mid_1, "sector_mid_2" to R.drawable.osm_sector_mid_2,
    "sector_def_0" to R.drawable.osm_sector_def_0, "sector_def_1" to R.drawable.osm_sector_def_1, "sector_def_2" to R.drawable.osm_sector_def_2,
    "mark_zona" to R.drawable.osm_mark_zona, "mark_homem" to R.drawable.osm_mark_homem,
    "offside_nao" to R.drawable.osm_offside_nao, "offside_sim" to R.drawable.osm_offside_sim,
)

@Composable
fun osmArt(name: String): Painter = painterResource(ART[name] ?: R.drawable.osm_sector_base)

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
