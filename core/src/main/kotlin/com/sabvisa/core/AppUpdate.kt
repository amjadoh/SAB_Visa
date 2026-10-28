package com.sabvisa.core

data class AppUpdate(val code: Int, val name: String, val tag: String, val assetId: Long=0, val bytes: Long=0, val sha256: String="") {
    init {
        require(code > 0)
        require(name.matches(Regex("[0-9][0-9A-Za-z.+_-]{0,63}")))
        require(tag.matches(Regex("v[0-9][0-9A-Za-z.+_-]{0,63}")))
        require(assetId>=0 && bytes in 0..104857600 && (sha256.isEmpty() || sha256.matches(Regex("[a-f0-9]{64}"))))
    }
    val pageUrl: String get() = "https://github.com/amjadoh/SAB_Visa/releases/tag/$tag"
    fun newerThan(installedCode: Int): Boolean = code > installedCode
}
