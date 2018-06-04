@file:JvmName("Elements")
package net.corda.gradle.jarfilter

import org.jetbrains.kotlin.metadata.deserialization.NameResolver
import org.jetbrains.kotlin.metadata.jvm.JvmProtoBuf
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import java.util.*

private const val DUMMY_PASSES = 1

internal abstract class Element(val name: String, val descriptor: String) {
    private var lifetime: Int = DUMMY_PASSES

    open val isExpired: Boolean get() = --lifetime < 0
}


internal class MethodElement(name: String, descriptor: String, val access: Int = 0) : Element(name, descriptor) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        other as MethodElement
        return other.name == name && other.descriptor == descriptor
    }
    override fun hashCode(): Int = Objects.hash(name, descriptor)
    override fun toString(): String = "MethodElement[name=$name, descriptor=$descriptor, access=$access]"
    override val isExpired: Boolean get() = access == 0 && super.isExpired
    val isConstructor: Boolean get() = isObjectConstructor || isClassConstructor
    val isClassConstructor: Boolean get() = name == "<clinit>"
    val isObjectConstructor: Boolean get() = name == "<init>"
    val isVoidFunction: Boolean get() = !isConstructor && descriptor.endsWith(")V")

    private val suffix: String
    val visibleName: String

    init {
        val idx = name.indexOf('$')
        visibleName = if (idx == -1) name else name.substring(0, idx)
        suffix = if (idx == -1) "" else name.drop(idx + 1)
    }

    fun isKotlinSynthetic(vararg tags: String): Boolean = (access and ACC_SYNTHETIC) != 0 && tags.contains(suffix)
}


/**
 * A class cannot have two fields with the same name but different types. However,
 * it can define extension functions and properties.
 */
internal class FieldElement(name: String, descriptor: String = "?", val extension: String = "()") : Element(name, descriptor) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        other as FieldElement
        return other.name == name && other.extension == extension
    }
    override fun hashCode(): Int = Objects.hash(name, extension)
    override fun toString(): String = "FieldElement[name=$name, descriptor=$descriptor, extension=$extension]"
    override val isExpired: Boolean get() = descriptor == "?" && super.isExpired
}

val String.extensionType: String get() = substring(0, 1 + indexOf(')'))

/**
 * Convert Kotlin getter/setter method data to [MethodElement] objects.
 */
internal fun JvmProtoBuf.JvmPropertySignature.toGetter(nameResolver: NameResolver): MethodElement? {
    return if (hasGetter()) { getter?.toMethodElement(nameResolver) } else { null }
}

internal fun JvmProtoBuf.JvmPropertySignature.toSetter(nameResolver: NameResolver): MethodElement? {
    return if (hasSetter()) { setter?.toMethodElement(nameResolver) } else { null }
}

internal fun JvmProtoBuf.JvmMethodSignature.toMethodElement(nameResolver: NameResolver)
    = MethodElement(nameResolver.getString(name), nameResolver.getString(desc))
