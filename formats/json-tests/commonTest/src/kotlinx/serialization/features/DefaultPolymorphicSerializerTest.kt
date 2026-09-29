/*
 * Copyright 2017-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
package kotlinx.serialization.features

import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
import kotlinx.serialization.test.*
import kotlin.test.*

class DefaultPolymorphicSerializerTest : JsonTestBase() {

    @Serializable
    abstract class Project {
        abstract val name: String
    }

    @Serializable
    data class DefaultProject(override val name: String, val type: String): Project()

    val module = SerializersModule {
        polymorphic(Project::class) {
            defaultDeserializer { DefaultProject.serializer() }
        }
    }

    private val json = Json { serializersModule = module }

    @Test
    fun test() = parametrizedTest {
        assertEquals(
            DefaultProject("example", "unknown"),
            json.decodeFromString<Project>(""" {"type":"unknown","name":"example"}""", it))
    }

    @Test
    fun defaultSerializerConflictWithDiscriminatorNotAllowed() = parametrizedTest { mode ->
        @Suppress("UNCHECKED_CAST") val module = SerializersModule {
            polymorphicDefaultSerializer(Project::class) {
                DefaultProject.serializer() as KSerializer<Project>
            }
        }
        val j = Json { serializersModule = module }
        checkEncodingException(mode, {
            j.encodeToString<Project>(DefaultProject("example", "custom"), mode)
        }) {
            message("Class 'kotlinx.serialization.features.DefaultPolymorphicSerializerTest.DefaultProject' cannot be serialized as base class 'kotlinx.serialization.Polymorphic<Project>' because it has property name that conflicts with JSON class discriminator 'type'.")
            serialName("kotlinx.serialization.features.DefaultPolymorphicSerializerTest.DefaultProject")
            hint("change class discriminator in JsonConfiguration, or rename property")
        }
    }

    @Serializable
    @SerialName("OwnedProject")
    data class OwnedProject(override val name: String, val owner: String) : Project()

    @Serializable
    sealed interface ChildWithPoly {
        @Serializable
        @SerialName("ChildLegacy")
        data class ChildLegacy(val s: String) : ChildWithPoly

        @Serializable
        @SerialName("ChildNew")
        data class ChildNew(val s: String, val type: String) : ChildWithPoly
    }

    @Serializable
    data class Parent(val child: ChildWithPoly)

    @Serializable
    data class ParentAround(val head: Int, val child: ChildWithPoly, val tail: String)

    @Serializable
    data class ParentList(val children: List<ChildWithPoly>, val tail: String)

    @Serializable
    data class NullableParent(val child: ChildWithPoly?, val tail: Int)

    @Serializable
    sealed interface Counted {
        @Serializable
        @SerialName("count")
        data class Count(val n: Int) : Counted
    }

    @Serializable
    data class NullableCount(val value: Counted?, val tail: Int)

    @Serializable
    data class CountList(val values: List<Counted?>, val tail: Int)

    private object CountFromInt : DeserializationStrategy<Counted> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CountFromInt", PrimitiveKind.INT)
        override fun deserialize(decoder: Decoder): Counted = Counted.Count(decoder.decodeInt())
    }

    private fun arrayJson(module: SerializersModule) = Json {
        useArrayPolymorphism = true
        serializersModule = module
    }

    @Test
    fun testArrayPolymorphismSealedMigration() = parametrizedTest { mode ->
        val seen = mutableListOf<String?>()
        val json = arrayJson(SerializersModule {
            polymorphicDefaultDeserializer(ChildWithPoly::class) { name ->
                seen += name
                if (name == null) ChildWithPoly.ChildLegacy.serializer() else null
            }
        })
        val legacy = ChildWithPoly.ChildLegacy("Hello World!")
        val parent = Parent(legacy)

        assertEquals(
            """{"child":["ChildLegacy",{"s":"Hello World!"}]}""",
            json.encodeToString(Parent.serializer(), parent, mode)
        )
        assertEquals(
            """["ChildLegacy",{"s":"Hello World!"}]""",
            json.encodeToString(ChildWithPoly.serializer(), legacy, mode)
        )

        seen.clear()
        assertEquals(parent, json.decodeFromString(Parent.serializer(), """{"child":{"s":"Hello World!"}}""", mode))
        assertEquals(listOf<String?>(null), seen)

        seen.clear()
        assertEquals(NullableParent(null, 5), json.decodeFromString(NullableParent.serializer(), """{"child":null,"tail":5}""", mode))
        assertEquals(emptyList<String?>(), seen)

        seen.clear()
        assertEquals(
            NullableParent(legacy, 5),
            json.decodeFromString(NullableParent.serializer(), """{"tail":5,"child":{"s":"Hello World!"}}""", mode)
        )
        assertEquals(listOf<String?>(null), seen)

        seen.clear()
        assertEquals(legacy, json.decodeFromString(ChildWithPoly.serializer(), """  {"s":"Hello World!"}  """, mode))
        assertEquals(listOf<String?>(null), seen)

        seen.clear()
        assertEquals(
            ParentAround(1, legacy, "z"),
            json.decodeFromString(
                ParentAround.serializer(),
                """{"tail":"z","child":{"s":"Hello World!"},"head":1}""",
                mode
            )
        )
        assertEquals(listOf<String?>(null), seen)

        seen.clear()
        val mixed = """
            {
              "children" : [ {"s":"a"} , ["ChildNew", {"s" : "b", "type": "kept"} ] , {"s":"c"} ] ,
              "tail" : "end"
            }
        """.trimIndent()
        assertEquals(
            ParentList(
                listOf(ChildWithPoly.ChildLegacy("a"), ChildWithPoly.ChildNew("b", "kept"), ChildWithPoly.ChildLegacy("c")),
                "end"
            ),
            json.decodeFromString(ParentList.serializer(), mixed, mode)
        )
        // Known array subtype bypasses the default; each unwrapped object is resolved once with null.
        assertEquals(listOf<String?>(null, null), seen)

        seen.clear()
        assertEquals(
            listOf(ChildWithPoly.ChildLegacy("a"), legacy, ChildWithPoly.ChildLegacy("c")),
            json.decodeFromString(
                ListSerializer(ChildWithPoly.serializer()),
                """[{"s":"a"},["ChildLegacy",{"s":"Hello World!"}],{"s":"c"}]""",
                mode
            )
        )
        assertEquals(listOf<String?>(null, null), seen)

        // A literal "type" property inside the unwrapped object is ordinary data, not a discriminator.
        val typedSeen = mutableListOf<String?>()
        val typed = arrayJson(SerializersModule {
            polymorphicDefaultDeserializer(ChildWithPoly::class) { name ->
                typedSeen += name
                if (name == null) ChildWithPoly.ChildNew.serializer() else null
            }
        })
        assertEquals(
            ChildWithPoly.ChildNew("payload", "ChildLegacy"),
            typed.decodeFromString(
                ChildWithPoly.serializer(),
                """{"type":"ChildLegacy","s":"payload"}""",
                mode
            )
        )
        assertEquals(listOf<String?>(null), typedSeen)
    }

    @Test
    fun testArrayPolymorphismOpenBaseRegistrationPaths() = parametrizedTest { mode ->
        val owned = OwnedProject("kotlinx.serialization", "kotlin")
        val registrations: List<(MutableList<String?>) -> SerializersModule> = listOf(
            { seen ->
                SerializersModule {
                    polymorphic(Project::class) { subclass(OwnedProject::class) }
                    polymorphicDefaultDeserializer(Project::class) { name ->
                        seen += name
                        if (name == null || name == "missing") DefaultProject.serializer() else null
                    }
                }
            },
            { seen ->
                SerializersModule {
                    polymorphic(Project::class) {
                        subclass(OwnedProject::class)
                        defaultDeserializer { name ->
                            seen += name
                            if (name == null || name == "missing") DefaultProject.serializer() else null
                        }
                    }
                }
            }
        )

        for (registration in registrations) {
            val seen = mutableListOf<String?>()
            val json = arrayJson(registration(seen))
            assertEquals(
                """["OwnedProject",{"name":"kotlinx.serialization","owner":"kotlin"}]""",
                json.encodeToString(Project.serializer(), owned, mode)
            )

            seen.clear()
            assertEquals(
                owned,
                json.decodeFromString(
                    Project.serializer(),
                    """["OwnedProject",{"name":"kotlinx.serialization","owner":"kotlin"}]""",
                    mode
                )
            )
            assertEquals(emptyList<String?>(), seen)

            seen.clear()
            assertEquals(
                DefaultProject("n", "t"),
                json.decodeFromString(Project.serializer(), """["missing",{"name":"n","type":"t"}]""", mode)
            )
            assertEquals(listOf<String?>("missing"), seen)

            seen.clear()
            assertEquals(
                DefaultProject("n", "OwnedProject"),
                json.decodeFromString(Project.serializer(), """ {"name":"n","type":"OwnedProject"} """, mode)
            )
            assertEquals(listOf<String?>(null), seen)
        }
    }

    @Test
    fun testArrayPolymorphismRejectsWithoutUnwrappedFallback() = parametrizedTest { mode ->
        val absent = arrayJson(SerializersModule {})
        val unwrapped = """{"child":{"s":"Hello World!"}}"""
        val absentError = assertFailsWith<SerializationException> {
            absent.decodeFromString(Parent.serializer(), unwrapped, mode)
        }
        val absentMessage = absentError.message.orEmpty()
        if (mode == JsonTestingMode.TREE) {
            assertTrue(absentMessage.contains("JsonArray"), absentMessage)
        } else {
            assertTrue(absentMessage.contains("Expected start of the array"), absentMessage)
        }

        val seen = mutableListOf<String?>()
        val nullProvider = arrayJson(SerializersModule {
            polymorphicDefaultDeserializer(ChildWithPoly::class) { name ->
                seen += name
                null
            }
        })
        seen.clear()
        assertFailsWith<SerializationException> {
            nullProvider.decodeFromString(Parent.serializer(), unwrapped, mode)
        }
        assertEquals(listOf<String?>(null), seen)

        // A strategy is available for null, but malformed arrays must not ask for it.
        val strict = arrayJson(SerializersModule {
            polymorphicDefaultDeserializer(ChildWithPoly::class) { name ->
                seen += name
                if (name == null) ChildWithPoly.ChildLegacy.serializer() else null
            }
        })
        val malformedRoots = listOf("[]", """["ChildLegacy"]""", "[", """["ChildLegacy",]""", "[1,{\"s\":\"x\"}]")
        for (input in malformedRoots) {
            seen.clear()
            assertFailsWith<SerializationException>(input) {
                strict.decodeFromString(ChildWithPoly.serializer(), input, mode)
            }
            assertTrue(seen.none { it == null }, "Fallback used for `$input`: $seen")
        }
        val malformedNested = listOf("""{"child":[]}""", """{"child":["ChildLegacy"]}""", """{"child":[}""", """{"head":1,"child":[],"tail":"z"}""")
        for (input in malformedNested) {
            seen.clear()
            assertFailsWith<SerializationException>(input) {
                if (input.contains("head")) {
                    strict.decodeFromString(ParentAround.serializer(), input, mode)
                } else {
                    strict.decodeFromString(Parent.serializer(), input, mode)
                }
            }
            assertTrue(seen.none { it == null }, "Fallback used for `$input`: $seen")
        }

        seen.clear()
        assertFailsWithMessage<SerializationException>("not found") {
            strict.decodeFromString(ChildWithPoly.serializer(), """["nope",{"s":"x"}]""", mode)
        }
        assertEquals(listOf<String?>("nope"), seen)
    }

    @Test
    fun testArrayPolymorphismPrimitiveNullableAndFailures() = parametrizedTest { mode ->
        val seen = mutableListOf<String?>()
        val json = arrayJson(SerializersModule {
            polymorphicDefaultDeserializer(Counted::class) { name ->
                seen += name
                if (name == null) CountFromInt else null
            }
        })

        assertEquals("""["count",{"n":1}]""", json.encodeToString(Counted.serializer(), Counted.Count(1), mode))

        seen.clear()
        assertEquals(Counted.Count(42), json.decodeFromString(Counted.serializer(), "  42 ", mode))
        assertEquals(listOf<String?>(null), seen)

        seen.clear()
        assertEquals(NullableCount(null, 7), json.decodeFromString(NullableCount.serializer(), """{"value":null,"tail":7}""", mode))
        assertEquals(emptyList<String?>(), seen)

        seen.clear()
        assertEquals(
            NullableCount(Counted.Count(3), 7),
            json.decodeFromString(NullableCount.serializer(), """{"tail":7,"value":3}""", mode)
        )
        assertEquals(listOf<String?>(null), seen)

        seen.clear()
        assertEquals(
            CountList(listOf(Counted.Count(1), null, Counted.Count(2)), 4),
            json.decodeFromString(CountList.serializer(), """{"values":[1, null, ["count",{"n":2}]],"tail":4}""", mode)
        )
        assertEquals(listOf<String?>(null), seen)

        seen.clear()
        assertFailsWith<SerializationException> {
            json.decodeFromString(Counted.serializer(), """{"n":1}""", mode)
        }
        assertEquals(listOf<String?>(null), seen)

        seen.clear()
        assertFailsWith<SerializationException> {
            json.decodeFromString(Counted.serializer(), "null", mode)
        }
        assertEquals(listOf<String?>(null), seen)

        // A raw array is the envelope, not an unwrapped list for the primitive strategy.
        seen.clear()
        assertFailsWith<SerializationException> {
            json.decodeFromString(Counted.serializer(), "[42]", mode)
        }
        assertEquals(emptyList<String?>(), seen)

        var calls = 0
        val throwing = arrayJson(SerializersModule {
            polymorphicDefaultDeserializer(Counted::class) {
                calls++
                throw SerializationException("provider failed")
            }
        })
        assertFailsWithMessage<SerializationException>("provider failed") {
            throwing.decodeFromString(Counted.serializer(), "42", mode)
        }
        assertEquals(1, calls)
    }

}
