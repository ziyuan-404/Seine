import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

val mochi = libs.mochi.compose.get()
val mochiCoordinate = "${mochi.module}:${mochi.versionConstraint.requiredVersion}"
val upstreamSources by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val upstreamAar by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

abstract class PrepareMochiSources : DefaultTask() {
    @get:Classpath abstract val sources: ConfigurableFileCollection
    @get:Classpath abstract val aar: ConfigurableFileCollection
    @get:OutputDirectory abstract val kotlinOutput: DirectoryProperty
    @get:OutputDirectory abstract val noticeOutput: DirectoryProperty
    @get:OutputDirectory abstract val profileOutput: DirectoryProperty
    @get:OutputFile abstract val consumerRules: RegularFileProperty

    @TaskAction
    fun prepare() {
        val kotlinRoot = kotlinOutput.get().asFile
        ZipFile(sources.singleFile).use { jar ->
            jar.entries().asSequence()
                .filter { it.name.startsWith("ui/feice/mochi/") && it.name.endsWith(".kt") }
                .forEach { entry ->
                    val target = kotlinRoot.resolve(entry.name)
                    check(target.normalize().startsWith(kotlinRoot))
                    target.parentFile.mkdirs()
                    jar.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
        }

        val geometry = kotlinRoot.resolve("ui/feice/mochi/basic/menu/DropdownGeometry.kt")
        val source = geometry.readText().replace("\r\n", "\n")
        val original = """
            val opening = smoothstep(0f, 0.15f, pg) * (1f - smoothstep(0.5f, 0.95f, pg))
            val near = proximityFuse(btn, panel, px.gap) * smoothstep(0.85f, 1f, pg)
            val goo = if (cover) 0f else px.goo * max(opening, near) * elastic
        """.trimIndent().prependIndent("    ")
        val connected = """
            // Use the demo's distance curve while the menu is opening or closing.
            val opening = smoothstep(0f, 0.15f, pg) * (1f - smoothstep(0.5f, 0.95f, pg))
            val near = proximityFuse(btn, panel, px.linkDistance)
            val goo = if (cover) 0f else px.goo * near * opening * elastic
        """.trimIndent().prependIndent("    ")
        val gap = "    val gap = with(d) { s.gap.toPx() }"
        val radius = "    val goo = with(d) { s.fuseRadius.toPx() }"
        check(source.contains(original) && source.contains(gap) && source.contains(radius)) {
            "Mochi's dropdown geometry changed. Review the MetaballPage distance patch."
        }
        geometry.writeText(
            source.replace(original, connected)
                .replace(gap, "$gap\n    val linkDistance = with(d) { 48.dp.toPx() }")
                .replace(radius, "    val goo = with(d) { minOf(s.fuseRadius, 22.dp).toPx() }")
        )

        val renderer = kotlinRoot.resolve("ui/feice/mochi/effect/metaball/MochiMetaball.kt")
        val rendererSource = renderer.readText()
        val shaderGate = "    val shaders = if (MochiGraphics.shaders) renderer.shaders else null"
        check(rendererSource.contains(shaderGate))
        renderer.writeText(
            rendererSource.replace(
                "import ui.feice.mochi.theme.MochiGraphics",
                "import ui.feice.mochi.theme.MochiGraphics\nimport ui.feice.mochi.theme.MochiGraphicsLevel",
            ).replace(
                shaderGate,
                "    val needsNeck = f.fuse > 0f && MochiGraphics.level >= MochiGraphicsLevel.Mid\n" +
                    "    val shaders = if (MochiGraphics.shaders || needsNeck) renderer.shaders else null",
            ).replace(
                "// API < 33 或图形档位低于「高」：没有融球和渐变，两块纯色",
                "// No runtime shader is available for this frame; draw the two solid shapes.",
            )
        )

        // The app uses Backdrop 2, whose effect API accepts Compose RenderEffect.
        val glass = kotlinRoot.resolve("ui/feice/mochi/effect/glass/engine/GlassPipeline.kt")
        val effect = "RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(cm)))"
        val glassSource = glass.readText()
        check(glassSource.contains("effect($effect)"))
        glass.writeText(
            glassSource.replace(
                "import androidx.compose.ui.geometry.Size",
                "import androidx.compose.ui.geometry.Size\nimport androidx.compose.ui.graphics.asComposeRenderEffect",
            ).replace("effect($effect)", "effect($effect.asComposeRenderEffect())")
        )

        ZipFile(aar.singleFile).use { zip ->
            zip.getInputStream(zip.getEntry("baseline-prof.txt")).use { input ->
                val profile = profileOutput.get().asFile.resolve("baseline-prof.txt")
                profile.parentFile.mkdirs()
                profile.outputStream().use { output -> input.copyTo(output) }
            }
            zip.getInputStream(zip.getEntry("proguard.txt")).use { input ->
                val rules = consumerRules.get().asFile
                rules.parentFile.mkdirs()
                rules.outputStream().use { output -> input.copyTo(output) }
            }
            ZipInputStream(zip.getInputStream(zip.getEntry("classes.jar"))).use { classes ->
                generateSequence { classes.nextEntry }
                    .filter { !it.isDirectory && it.name.startsWith("META-INF/mochi-compose/") }
                    .forEach { entry ->
                        val target = noticeOutput.get().asFile.resolve("META-INF/mochi-compose/${entry.name.substringAfterLast('/')}")
                        target.parentFile.mkdirs()
                        target.outputStream().use { output -> classes.copyTo(output) }
                    }
            }
        }
    }
}

val prepareMochiSources by tasks.registering(PrepareMochiSources::class) {
    sources.from(upstreamSources)
    aar.from(upstreamAar)
    kotlinOutput.set(layout.buildDirectory.dir("generated/mochi/kotlin"))
    noticeOutput.set(layout.buildDirectory.dir("generated/mochi/resources"))
    profileOutput.set(layout.buildDirectory.dir("generated/mochi/baselineProfiles"))
    consumerRules.set(layout.buildDirectory.file("generated/mochi/consumer-rules.pro"))
}

android {
    namespace = "ui.feice.mochi"
    compileSdk = 37
    defaultConfig {
        minSdk = 36
        consumerProguardFiles(layout.buildDirectory.file("generated/mochi/consumer-rules.pro").get().asFile)
    }
    buildFeatures.compose = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild").configure { dependsOn(prepareMochiSources) }

kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(prepareMochiSources, PrepareMochiSources::kotlinOutput)
    variant.sources.resources?.addGeneratedSourceDirectory(prepareMochiSources, PrepareMochiSources::noticeOutput)
    variant.sources.baselineProfiles?.addGeneratedSourceDirectory(prepareMochiSources, PrepareMochiSources::profileOutput)
}

dependencies {
    upstreamSources("$mochiCoordinate:sources@jar")
    upstreamAar("$mochiCoordinate@aar")

    api(platform(libs.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.foundation)
    api(libs.capsule)
    api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    implementation(libs.backdrop)
    implementation(libs.androidx.activity.compose)
    implementation("com.materialkolor:material-color-utilities:4.1.1")
}
