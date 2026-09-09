package com.rokid.glass.mediastream.capture

import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicApiSurfaceTest {
    @Test
    fun `Java customers cannot call an NV21 frame construction entry point`() {
        val javaFacingConstructors = Nv21Frame::class.java.declaredConstructors.filter { constructor ->
            Modifier.isPublic(constructor.modifiers) && !constructor.isSynthetic
        }
        val javaFacingFactories = Nv21Frame::class.java.declaredClasses
            .flatMap { nestedClass -> nestedClass.declaredMethods.toList() }
            .filter { method ->
                method.returnType == Nv21Frame::class.java &&
                    Modifier.isPublic(method.modifiers) &&
                    !method.isSynthetic
            }

        assertTrue(
            "Unexpected Java-facing construction entry points: " +
                "constructors=$javaFacingConstructors, factories=$javaFacingFactories",
            javaFacingConstructors.isEmpty() && javaFacingFactories.isEmpty(),
        )
    }

    @Test
    fun `NV21 frame implementation types are absent from the public JVM surface`() {
        val moduleImplementationTypes = Nv21Frame::class.java.declaredFields
            .filterNot { field -> Modifier.isStatic(field.modifiers) }
            .map { field -> field.type }
            .filter { type ->
                type != Nv21Frame::class.java && type.name.startsWith(CAPTURE_PACKAGE_PREFIX)
            }

        assertFalse(
            "The ABI check must observe the frame ownership implementation type",
            moduleImplementationTypes.isEmpty(),
        )
        assertTrue(
            "Frame ownership types must remain private: $moduleImplementationTypes",
            moduleImplementationTypes.all { type -> Modifier.isPrivate(type.modifiers) },
        )
    }

    private companion object {
        const val CAPTURE_PACKAGE_PREFIX = "com.rokid.glass.mediastream.capture."
    }
}
