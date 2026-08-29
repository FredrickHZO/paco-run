package dev.fred.pacorun.settings

data class RunPreferences(
    val minKmh: Float = 7.5f,
    val maxKmh: Float = 8.5f,
    val confirmationSamples: Int = 2,
    val repeatMillis: Long = 5_000L,
    val gpsCuesEnabled: Boolean = false,
    val cueVolume: Float = .6f,
    val dynamicColor: Boolean = true,
) {
    val isValid: Boolean get() = minKmh < maxKmh
}
