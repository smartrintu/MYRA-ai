package com.myra.assistant.license

/** Public half of the signing key. Only the owner holds the private half (admin page). */
object LicenseConfig {
    const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE4m/OfggPH5XbpXpu/pJNFWPeRZ8C0MWgQVNg5HNK5++/E9Krm7wRd8V83j8Y14uazHRHErY32Fz/wJ6SdTknvw=="

    /** Shown on the activation screen. */
    const val CONTACT_TEXT = "Key ke liye Smart Rintu se sampark karo aur apna Device ID bhejo."
}
