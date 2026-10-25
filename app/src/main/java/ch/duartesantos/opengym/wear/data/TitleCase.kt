package ch.duartesantos.opengym.wear.data

/**
 * An exercise name in Title Case, for display only.
 *
 * openGym stores each exercise name exactly as it was typed or imported, so the catalogue mixes
 * `Lat Pulldown` with `cable lat pulldown`. Every name the watch renders goes through here, so the
 * workout list, the exercise page and the rest page agree. Only the first letter of a word is
 * raised and the rest is left alone, so acronyms (`EZ-Bar`, `RDL`) survive. A word starts at the
 * start of the string or after anything that is not a letter, digit or apostrophe, so `pull-up`,
 * `t-bar` and `farmer's walk` become `Pull-Up`, `T-Bar` and `Farmer's Walk`.
 */
internal fun String.titleCased(): String {
  val out = StringBuilder(length)
  var wordStart = true
  for (ch in this) {
    out.append(if (wordStart && ch.isLetter()) ch.uppercaseChar() else ch)
    wordStart = !ch.isLetterOrDigit() && ch != '\'' && ch != '’'
  }
  return out.toString()
}
