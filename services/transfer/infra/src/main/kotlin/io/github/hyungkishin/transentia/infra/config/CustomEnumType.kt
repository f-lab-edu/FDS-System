package io.github.hyungkishin.transentia.infra.config

import org.hibernate.HibernateException
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.usertype.DynamicParameterizedType
import org.hibernate.usertype.UserType
import java.io.Serializable
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.Properties
import kotlin.reflect.full.memberProperties

/**
 * 문자열 기반 Enum 매핑 (VARCHAR)
 * - enum에 `val code: String`이 있으면 그 값을 저장/조회
 * - 없으면 enum.name 사용
 * 사용법: Persistance Layer 내에 entity 에 아래와 같이 사용.
 * ```
 * @Type(CustomEnumType::class)
 * @Column(nullable = false)
 * val status: TransactionStatus
 * ```
 *
 * TODO ?: 공용 스타터로 빼는게 나을것 같기도 하다. ( 여러 서비스에서 재사용 되는 시점이 올때 )
 */
class CustomEnumType : UserType<Any>, DynamicParameterizedType {

    private lateinit var enumClass: Class<*>
    private lateinit var enumConstants: Array<Any?>
    private lateinit var toCode: (Any?) -> String
    private lateinit var fromCode: (String) -> Any?

    override fun setParameterValues(parameters: Properties) {
        val pType = parameters[DynamicParameterizedType.PARAMETER_TYPE] as? DynamicParameterizedType.ParameterType
            ?: throw HibernateException("Cannot resolve ParameterType for CustomEnumType")
        enumClass = pType.returnedClass
        if (!enumClass.isEnum) throw HibernateException("${enumClass.name} is not an enum")

        @Suppress("UNCHECKED_CAST")
        enumConstants = enumClass.enumConstants as? Array<Any?>
            ?: throw HibernateException("No enum constants for ${enumClass.name}")

        // code:String 프로퍼티 자동 탐지
        val codeProp = enumClass.kotlin.memberProperties
            .firstOrNull { it.name == "code" && it.returnType.classifier == String::class }

        if (codeProp != null) {
            // code 프로퍼티 사용
            toCode = { instance ->
                (codeProp.getter.call(instance) as? String)
                    ?: throw HibernateException("Property 'code' of ${enumClass.simpleName} must be String")
            }
            val index = enumConstants.associateBy { toCode(it) }  // code -> enum
            fromCode = { c -> index[c] }
        } else {
            // fallback: enum.name 사용
            toCode = { (it as Enum<*>).name }
            val index = enumConstants.associateBy { (it as Enum<*>).name } // name -> enum
            fromCode = { c -> index[c] }
        }
    }

    override fun getSqlType(): Int = Types.VARCHAR

    @Suppress("UNCHECKED_CAST")
    override fun returnedClass(): Class<Any> = enumClass as Class<Any>

    override fun equals(x: Any?, y: Any?): Boolean = x === y || x == y
    override fun hashCode(x: Any?): Int = x?.hashCode() ?: 0
    override fun deepCopy(value: Any?): Any? = value
    override fun isMutable(): Boolean = false
    override fun disassemble(value: Any?): Serializable? = value as? Serializable
    override fun assemble(cached: Serializable?, owner: Any?): Any? = cached
    override fun replace(original: Any?, target: Any?, owner: Any?): Any? = original

    override fun nullSafeGet(
        rs: ResultSet,
        position: Int,
        session: SharedSessionContractImplementor?,
        owner: Any?
    ): Any? {
        val s = rs.getString(position) ?: return null
        return fromCode(s) ?: throw HibernateException("Unknown enum code '$s' for ${enumClass.name}")
    }

    override fun nullSafeSet(
        st: PreparedStatement,
        value: Any?,
        index: Int,
        session: SharedSessionContractImplementor?
    ) {
        if (value == null) {
            st.setNull(index, Types.VARCHAR); return
        }
        if (!enumClass.isInstance(value)) {
            throw HibernateException("Expected ${enumClass.name}, got ${value::class.java.name}")
        }
        st.setString(index, toCode(value))
    }
}
