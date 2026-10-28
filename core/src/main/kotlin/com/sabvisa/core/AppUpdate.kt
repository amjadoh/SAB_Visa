package com.sabvisa.core

data class AppUpdate(val code: Int, val name: String, val tag: String) {
    init {
        require(code > 0)
        require(name.matches(Regex("[0-9][0-9A-Za-z.+_-]{0,63}")))
        require(tag.matches(Regex("v[0-9][0-9A-Za-z.+_-]{0,63}")))
    }
    val pageUrl: String get() = "https://github.com/amjadoh/SAB_Visa/releases/tag/$tag"
    fun newerThan(installedCode: Int): Boolean = code > installedCode
}
