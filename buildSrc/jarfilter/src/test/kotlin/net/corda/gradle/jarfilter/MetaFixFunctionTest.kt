package net.corda.gradle.jarfilter

import net.corda.gradle.unwanted.*
import net.corda.gradle.jarfilter.asm.*
import net.corda.gradle.jarfilter.matcher.*
import org.gradle.api.logging.Logger
import org.hamcrest.core.IsCollectionContaining.hasItem
import org.hamcrest.core.IsNot.not
import org.junit.Assert.*
import org.junit.Test
import kotlin.jvm.kotlin
import kotlin.reflect.full.declaredFunctions

class MetaFixFunctionTest {
    companion object {
        private val logger: Logger = StdOutLogging(MetaFixFunctionTest::class)
        private val longData = isFunction("longData", Long::class)
        private val unwantedFun = isFunction(
            name = "unwantedFun",
            returnType = String::class,
            parameters = *arrayOf(String::class)
        )
    }

    @Test
    fun testFunctionRemovedFromMetadata() {
        val bytecode = recodeMetadataFor<WithFunction, MetadataTemplate>()
        val sourceClass = bytecode.toClass<WithFunction, HasLong>()

        // Check that the unwanted function has been successfully
        // added to the metadata, and that the class is valid.
        val sourceObj = sourceClass.newInstance()
        assertEquals(BIG_NUMBER, sourceObj.longData())
        assertThat("unwantedFun(String) not found", sourceClass.kotlin.declaredFunctions, hasItem(unwantedFun))
        assertThat("longData not found", sourceClass.kotlin.declaredFunctions, hasItem(longData))

        // Rewrite the metadata according to the contents of the bytecode.
        val fixedClass = bytecode.fixMetadata(logger).toClass<WithFunction, HasLong>()
        val fixedObj = fixedClass.newInstance()
        assertEquals(BIG_NUMBER, fixedObj.longData())
        assertThat("unwantedFun(String) still exists", fixedClass.kotlin.declaredFunctions, not(hasItem(unwantedFun)))
        assertThat("longData not found", fixedClass.kotlin.declaredFunctions, hasItem(longData))
    }

    class MetadataTemplate : HasLong {
        override fun longData(): Long = 0
        @Suppress("UNUSED") fun unwantedFun(str: String): String = "UNWANTED[$str]"
    }
}

class WithFunction : HasLong {
    override fun longData(): Long = BIG_NUMBER
}
