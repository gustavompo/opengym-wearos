package ch.duartesantos.opengym.wear.theme

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme

/*
 * Palette transcribed from docs/research/00-ux-references.md (the Hevy Wear OS screenshots) and
 * then *corrected by pixel measurement* of `references/exercise-page.png` and
 * `references/rest-period-timer.png`. The research doc's two grey and its blue were transcriptions,
 * not measurements, and all three were wrong:
 *
 *   token          doc said     measured from the reference pixels
 *   accent blue    #2196F3      #1D82EA (29,130,234) - exact, in both reference files
 *   surface grey   #2C2C2C      #1C1C1E (28,28,30)  - 2.6x darker
 *   raised grey    #333333      #1C1C1E            - the reference has ONE neutral, see below
 *
 * Whites (255,255,255) and text greys come through the reference unshifted, so these are not a
 * colour-profile artefact. See docs/notes/06-rest-timer-measurement.md section 5 and
 * docs/notes/07-integration.md.
 *
 * This is not a generated Material tonal palette. The reference design is "pure black background,
 * one blue accent, one dark neutral, white text" - so that is all that exists here. Anything a
 * screen needs beyond these values should be a deliberate addition, not a guess.
 *
 * That is the rule the 2026-10-11 UI pass followed. Gus: "slightly richer … don't over do it", and
 * "the buttons … can be a slightly more bright tone of grey … so I can more easily see where the
 * button ends". So the one measured neutral became a **four-rung ladder** - [OpenGymSurface]
 * (`#1C1C1E`, unchanged and still the only measured value), [OpenGymSurfaceRaised] (`#242426`, an
 * active row), [OpenGymSurfaceHigh] (`#2C2C2E`, a button - this is the one the user asked for) and
 * [OpenGymOutline] (`#38383A`, a border) - and **no hue was added**, because no state on any screen
 * is carried by colour alone. Reasoning, and the per-screen token mapping the screen agents must
 * use, is in `docs/notes/29-ui-foundation-findings.md`.
 */

/** Screen background. */
val OpenGymBlack: Color = Color(0xFF000000)

/** Accent: the primary action, the rest progress arc, the active spinner value. Measured. */
val OpenGymBlue: Color = Color(0xFF1D82EA)

/** Pills and spinner cards. Measured on the rest pills and the spinner-card outline. */
val OpenGymSurface: Color = Color(0xFF1C1C1E)

/**
 * Selected / active surface: the one row of a list that must read as "this one is active" with no
 * colour at all, and a card that is the focus of its screen.
 *
 * Why it exists: [OpenGymSurface] is the base fill of *every* row and card, so a selected row fills
 * with exactly the same value as an unselected one and the state is invisible in a screenshot. This
 * is the rung between the base fill and a control: `#242426` is the arithmetic midpoint of the two
 * measured neutrals ([OpenGymSurface] `#1C1C1E` and [OpenGymSurfaceHigh] `#2C2C2E`), i.e. +8 on
 * every channel each way - not a value anybody invented.
 *
 * It is deliberately *quieter* than [OpenGymSurfaceHigh]: the step a fill needs scales inversely
 * with the area it covers. A selected row is a 264 x 89 px slab, and lifting it by a button's +16
 * turns it into a washed-out panel. Its edge carries the rest of the signal, so pair it with
 * [OpenGymOutline] when the row must also read as a discrete object.
 */
val OpenGymSurfaceRaised: Color = Color(0xFF242426)

/**
 * Raised surface: the fill of a **button or other control** - the chevron circles, the rest pills,
 * `Skip`.
 *
 * Why it exists / why it is this value: the user asked for exactly this ("the buttons … can be a
 * slightly more bright tone of grey, just a little bit, just so I can more easily see where the
 * button ends"). It used to be `#1C1C1E`, the same neutral as every card and outline in the app, so
 * a 51 px chevron circle had **no edge at all** against the `#000000` page - 28/255 of luminance is
 * the same step as no fill.
 *
 * `#2C2C2E` is the low end of the range the source app used for its secondary surface
 * (`docs/research/00-ux-references.md`: `#2C2C2C`-`#333333`), and it is the value directly above
 * [OpenGymSurface] in the same neutral family. It is a uniform **+16** on all three channels
 * (28,28,30 -> 44,44,46), which is the smallest step that is unambiguous on an OLED watch at 1x
 * while leaving white text the brightest thing on the screen. `#333336` (+23) is rejected: at that
 * value the buttons compete with the labels above them, and the reference's rule is that emphasis
 * is by colour, not by mass.
 */
val OpenGymSurfaceHigh: Color = Color(0xFF2C2C2E)

/**
 * The 1 px-ish border that makes a control's or a card's edge visible without adding weight.
 *
 * Why it exists: `Modifier.border` paints *on* the shape's edge, so an outline drawn in the fill's
 * own colour marks nothing - `ExercisePage` and `WorkoutPage` both currently border a `#1C1C1E`
 * card with `#1C1C1E`, which is a no-op the eye cannot find. An outline also cannot be darker than
 * what it surrounds, or it reads as a hole rather than an edge.
 *
 * `#38383A` is +12 above [OpenGymSurfaceHigh], the next rung of the same neutral family, and is the
 * "separator" level of it. It is still only 56/255: visible against black and against a filled
 * card, never bright enough to draw the eye away from text.
 */
val OpenGymOutline: Color = Color(0xFF38383A)

/** Primary text, and the selected value in the weight spinner. */
val OpenGymWhite: Color = Color(0xFFFFFFFF)

/** Muted text: the `Kg` / `Reps` labels, spinner neighbours. */
val OpenGymMuted: Color = Color(0xFFAAAAAA)

/** The reference gives a range; this is its upper end, for hint text that needs a little more. */
val OpenGymMutedStrong: Color = Color(0xFFB0B0B0)

/**
 * Fixed Wear Material 3 [ColorScheme]. Deliberately *not* dynamic colour: the reference is a
 * black-and-blue design and the watch's wallpaper must not repaint it.
 *
 * Tokens the reference says nothing about (errors, tertiary) keep their stock Wear Material 3
 * values from [ColorScheme].
 */
internal val OpenGymColorScheme: ColorScheme =
  ColorScheme().copy(
    primary = OpenGymBlue,
    onPrimary = OpenGymWhite,
    // The tonal "pill" surfaces in the reference are the grey cards/pills, not a blue tint.
    primaryContainer = OpenGymSurfaceHigh,
    onPrimaryContainer = OpenGymWhite,
    secondaryContainer = OpenGymSurface,
    onSecondaryContainer = OpenGymWhite,
    background = OpenGymBlack,
    onBackground = OpenGymWhite,
    surfaceContainerLow = OpenGymBlack,
    surfaceContainer = OpenGymSurface,
    surfaceContainerHigh = OpenGymSurfaceHigh,
    onSurface = OpenGymWhite,
    onSurfaceVariant = OpenGymMuted,
    outline = OpenGymMuted,
    // The neutral border token, not the fill: `outlineVariant` is what Wear M3 draws a card's or a
    // divider's edge with, and `OpenGymSurfaceHigh` would make that edge a no-op on a filled card.
    outlineVariant = OpenGymOutline,
  )
