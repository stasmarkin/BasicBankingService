package me.stasmarkin.simplebank.util

import com.beust.klaxon.Klaxon

class ResourceReader {
    companion object {
        fun read(filePath : String): String {
            return ResourceReader::class.java.getResource(filePath).readText()
        }

        inline fun <reified T> json(filePath : String): T {
            return Klaxon()
                .parse<T>(read(filePath))!!
        }
    }
}