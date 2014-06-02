package com.voltvoodoo.saplo4j.model

import java.io.Serializable

public open class Language(protected var code: String?) : Serializable {

    public open fun equals(other: Language?): Boolean {
        return other?.toString()?.equals(this.toString())!!
    }

    class object {
        public var ENGLISH: Language? = Language("en")
        public var SWEDISH: Language? = Language("sv")

        private val serialVersionUID: Long = -2442762969929206780
    }
}