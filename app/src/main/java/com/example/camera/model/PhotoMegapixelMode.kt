package com.example.camera.model

/**
 * Megapixel capture mode for photo mode:
 * - M12: Standard 12MP Quad-Bayer pixel binned
 * - M50: 50MP Real-ESRGAN AI Super Resolution
 * - M100: 100MP Real-ESRGAN AI Super Resolution
 * - M200: 200MP Real-ESRGAN AI Super Resolution
 */
enum class PhotoMegapixelMode(
    val label: String,
    val megapixels: Int,
    val description: String,
    val isSuperResolution: Boolean = false
) {
    M12("12M", 12, "Standard 12MP (4-in-1 Binned)", false),
    M50("50M", 50, "50MP Real-ESRGAN AI Super Resolution", true),
    M100("100M", 100, "100MP Real-ESRGAN AI Super Resolution", true),
    M200("200M", 200, "200MP Real-ESRGAN AI Super Resolution", true)
}
