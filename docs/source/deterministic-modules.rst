Deterministic Corda Modules
===========================

A Corda contract's verify function should always produce the same results for the same input data. To that end,
Corda provides the following modules:
 
 #. ``core-deterministic``
 #. ``serialization-deterministic``
 #. ``jdk8u-deterministic``

These are reduced version of Corda's ``core`` and ``serialization`` modules and the OpenJDK 8 ``rt.jar``, where the
non-deterministic functionality has been removed. The intention here is that all CorDapp classes required for
contract verification should be compiled against these modules to prevent them containing non-deterministic behaviour.

.. note:: These modules are only a development aid. They cannot guarantee determinism without also including
          deterministic versions of all their dependent libraries, e.g. ``kotlin-stdlib``.

Generating the Deterministic Modules
------------------------------------

JDK 8
  ``jdk8u-deterministic`` is a "pseudo JDK" image that we can point the Java and Kotlin compilers to. It downloads its
  ``rt.jar`` containing our deterministic subset of the Java 8 APIs from the Artifactory.

  To build a new version of this JAR and upload it to the Artifactory, see the ``create-jdk8u`` module. This is a
  standalone Gradle project within the Corda repository that will clone the ``deterministic-jvm8`` branch of Corda's
  `OpenJDK repository <https://github.com/corda/openjdk>`_ and then build it. (This currently requires a C++ compiler,
  GNU Make and a UNIX-like development environment.)

Corda Modules
  ``core-deterministic`` and ``serialization-deterministic`` are generated from Corda's ``core`` and ``serialization``
  modules respectively using both `ProGuard <https://www.guardsquare.com/en/proguard>`_ and Corda's ``JarFilter`` Gradle
  plugin. Corda developers configure these tools by applying Corda's ``@Deterministic`` and ``@NonDeterministic``
  annotations to elements of ``core`` and ``serialization`` as described `here <deterministic_annotations_>`_.

The build generates each of Corda's deterministic JARs in six steps:

 #. Some *very few* classes in the original JAR must be replaced completely. This is typically because the original
    class uses something like ``ThreadLocal``, which is not available in the deterministic Java APIs, and yet the
    class is still required by the deterministic JAR. We must keep such classes to a minimum!
 #. The patched JAR is analysed by ProGuard for the first time using the following rule:

    .. sourcecode:: groovy

        keep '@interface net.corda.core.Deterministic { *; }'

    ..

    ProGuard works by calculating how much code is reachable from given "entry points", and in our case these entry
    points are the ``@Deterministic`` classes. The unreachable classes are then discarded by ProGuard's ``shrink``
    option.
 #. The remaining classes may still contain non-deterministic code. However, there is no way of writing a ProGuard rule
    explicitly to discard anything. Consider the following class:

    .. sourcecode:: kotlin

        @CordaSerializable
        @Deterministic
        data class UniqueIdentifier(val externalId: String?, val id: UUID) : Comparable<UniqueIdentifier> {
            @NonDeterministic constructor(externalId: String?) : this(externalId, UUID.randomUUID())
            @NonDeterministic constructor() : this(null)
            ...
        }

    ..

    While CorDapps will definitely need to handle ``UniqueIdentifier`` objects, both of the secondary constructors
    generate a new random ``UUID`` and so are non-deterministic. Hence the next "determinising" step is to pass the
    classes to the ``JarFilter`` tool, which strips out all of the elements which have been annotated as
    ``@NonDeterministic`` and stubs out any functions annotated with ``@NonDeterministicStub``. (Stub functions that
    return a value will throw ``UnsupportedOperationException``, whereas ``void`` or ``Unit`` stubs will do nothing.)
 #. After the ``@NonDeterministic`` elements have been filtered out, the classes are rescanned using ProGuard to remove
    any more code that has now become unreachable.
 #. The remaining classes define our deterministic subset. However, the ``@kotlin.Metadata`` annotations on the compiled
    Kotlin classes still contain references to all of the functions and properties that ProGuard has deleted. Therefore
    we now use the ``JarFilter`` to delete these references, as otherwise the Kotlin compiler will pretend that the
    deleted functions and properties are still present.
 #. Finally, we use ProGuard again to validate our JAR against the deterministic ``rt.jar``:

    .. sourcecode:: groovy

        task checkDeterminism(type: ProGuardTask, dependsOn: jdkTask) {
            injars metafix

            libraryjars "$deterministic_jdk_home/jre/lib/rt.jar"

            configurations.runtimeLibraries.forEach {
                libraryjars it.path, filter: '!META-INF/versions/**'
            }

            keepattributes '*'
            dontpreverify
            dontobfuscate
            dontoptimize
            verbose

            keep 'class *'
        }

    ..

    This step will fail if ProGuard spots any Java API references that still cannot be satisfied by the deterministic
    ``rt.jar``, and hence it will break the build.

.. _deterministic_annotations:

Applying ``@Deterministic`` and ``@NonDeterministic`` annotations
-----------------------------------------------------------------

Corda developers need to understand how to annotate classes in the ``core`` and ``serialization`` modules correctly
in order to maintain the deterministic JARs.

.. note:: Every Kotlin class still has its own ``.class`` file, even when all of those classes share the same
          source file. Also, when you annotate the file:

          .. sourcecode:: kotlin

              @file:Deterministic
              package net.corda.core.internal

          ..

          you *do not* annotate any ``class`` declared *within* this file. You merely annotate the accompanying
          Kotlin ``xxxKt.class``.

Deterministic Classes
    Classes that *must* be included in the deterministic JAR should be annotated as ``@Deterministic``.

    .. sourcecode:: kotlin

        @Target(FILE, CLASS)
        @Retention(BINARY)
        @CordaInternal
        annotation class Deterministic
    ..

    To preserve any Kotlin functions or properties that have been declared outside of a ``class``, you should
    annotate the source file's ``package`` declaration instead:

    .. sourcecode:: kotlin

        @file:JvmName("InternalUtils")
        @file:Deterministic
        package net.corda.core.internal

        infix fun Temporal.until(endExclusive: Temporal): Duration = Duration.between(this, endExclusive)

    ..

Non-Deterministic Elements
    Elements that *must* be deleted from classes in the deterministic JAR should be annotated as ``@NonDeterministic``.

    .. sourcecode:: kotlin

        @Target(
            FILE,
            CLASS,
            CONSTRUCTOR,
            FUNCTION,
            PROPERTY_GETTER,
            PROPERTY_SETTER,
            PROPERTY,
            FIELD,
            TYPEALIAS
        )
        @Retention(BINARY)
        @CordaInternal
        annotation class NonDeterministic

    ..

    You must also ensure that a deterministic class's primary constructor does not reference any classes that are
    not available in the deterministic ``rt.jar``, nor have any non-deterministic default parameter values such as
    ``UUID.randomUUID()``. The biggest risk here would be that ``JarFilter`` would delete the primary constructor
    and that the class could no longer be instantiated, although ``JarFilter`` will print a warning in this case.
    However, it is also likely that the "determinised" class would have a different serialisation signature than
    its non-deterministic version and so become unserialisable on the deterministic JVM.

    Be aware that package-scoped Kotlin properties are all initialised within a commin ``<clinit>`` block inside
    their host ``.class`` file. This means that when ``JarFilter`` deletes these properties, it cannot also remove
    their initialisation code. For example:

    .. sourcecode:: kotlin

        package net.corda.core

        @NonDeterministic
        val map: MutableMap<String, String> = ConcurrentHashMap()

    ..

    In this case, ``JarFilter`` would delete the ``map`` property but the ``<clinit>`` block would still create
    an instance of ``ConcurrentHashMap``. The solution here is to refactor the property into its own file and then
    annotate the file itself as ``@NonDeterministic`` instead.

Non Deterministic Function Stubs
    Sometimes it is impossible to delete a function entirely. Or a function may have some non-deterministic code
    embedded inside it that cannot be removed. For these rare cases, there is the ``@NonDeterministicStub``
    annotation:

    .. sourcecode:: kotlin

        @Target(
            CONSTRUCTOR,
            FUNCTION,
            PROPERTY_GETTER,
            PROPERTY_SETTER
        )
        @Retention(BINARY)
        @CordaInternal
        annotation class NonDeterministicStub

    ..

    This annotation instructs ``JarFilter`` to replace the function's body with either an empty body (for functions
    that return ``void`` or ``Unit``) or one that throws ``UnsupportedOperationException``. For example:

    .. sourcecode:: kotlin

        fun necessaryCode() {
            nonDeterministicOperations()
            otherOperations()
        }

        @NonDeterministicStub
        private fun nonDeterministicOperations() {
            // etc
        }

    ..

