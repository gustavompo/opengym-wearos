package ch.duartesantos.opengym.wear.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography

/**
 * The app theme: the [OpenGymColorScheme] palette on top of Wear Material 3's own type and shape
 * scales.
 *
 * Called from `WearApp` so that both the real activity and the Wear previews get it. There is no
 * dynamic-colour path on purpose — the reference design is black and blue.
 */
@Composable
fun OpenGymWearTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = OpenGymColorScheme, typography = OpenGymTypography, content = content)
}

/**
 * Type roles, mapped from the reference's rule ("one dominant focal value per screen, everything
 * else one to three tiers smaller and muted"; "emphasis is by colour, not size").
 *
 * Nothing is redefined: Wear Material 3 already carries what the reference needs, and redefining
 * sizes here would fight the user's font-size setting. The load-bearing fact is that the
 * `numeral*` roles use a tabular-figure font, which is exactly what the `mm:ss` rest countdown and
 * the spinner values require — so use them instead of `display*`:
 *
 * | Role in the reference | Wear Material 3 slot |
 * |---|---|
 * | countdown `01:29`, selected spinner value | `typography.numeralLarge` |
 * | spinner neighbours above and below | `typography.numeralSmall` |
 * | `Kg`, `Reps`, `Set 1/4` labels | `typography.labelSmall` |
 * | `prev. 50.0 kg x 10`, `Next: …` | `typography.bodySmall` |
 * | exercise title | `typography.titleMedium` |
 */
internal val OpenGymTypography: Typography = Typography()

/**
 * The only spacing the reference implies: a small gap between a spinner value and its label, a
 * normal gap between stacked blocks, and the outer inset for content.
 */
object OpenGymSpacing {
  val small = 4.dp
  val medium = 8.dp
  val large = 16.dp
}
