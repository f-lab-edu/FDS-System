package io.github.hyungkishin.transentia.common.http.model

import java.io.Serializable

/**
 * Enum <-> DB 코드 매핑용 기본 인터페이스
 */
interface BaseEnum : Serializable {
    val code: Any

    companion object {
        @JvmStatic
        fun <S> getEnum(cls: Class<S>, code: Any): S? where S : Enum<*>, S : BaseEnum {
            return cls.enumConstants?.firstOrNull { it.code == code }
        }
    }
}