package arx.core

import arx.display.ascii.AsciiCanvas
import arx.display.core.Image
import arx.display.core.SentinelAmgRef
import arx.display.core.SentinelImage
import arx.display.core.Shader
import com.typesafe.config.*
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object Resources {
    val projectName: String? = System.getProperty("projectName")
    val projectSuffix = projectName?.let { "$it/" } ?: ""
    val baseAssetPath = System.getProperty("assetPath") ?: "src/main/resources/"
    val assetPath = baseAssetPath + projectSuffix
    val arxAssetPath = baseAssetPath + "arx/"
    private var images = ConcurrentHashMap<String, Image>()
    private var amgs = ConcurrentHashMap<String, AsciiCanvas>()
    private var shaders = ConcurrentHashMap<String, Shader>()
    private var typefaces = ConcurrentHashMap<String, ArxTypeface>()
    private var configs = ConcurrentHashMap<String, Config>()


    fun pickPath(str: String): String {
        return if (str.startsWith("/") && Files.exists(Path.of(str))) {
            str
        } else if (str.startsWith("/") && Files.exists(Path.of(str.substring(1)))) {
            str.substring(1)
        } else if (Files.exists(Path.of(assetPath + str))) {
            assetPath + str
        } else if (Files.exists(Path.of(baseAssetPath + str))) {
            baseAssetPath + str
        } else if (Files.exists(Path.of(arxAssetPath + str))) {
            arxAssetPath + str
        } else {
            assetPath + str
        }
    }

    fun toRelativePath(f: File): String {
        return toRelativePath(f.absolutePath)
    }

    fun toRelativePath(absP: String): String {
        return if (absP.contains(assetPath)) {
            absP.substringAfter(assetPath)
        } else if (absP.contains(baseAssetPath)) {
            absP.substringAfter(baseAssetPath)
        } else if (absP.contains(arxAssetPath)) {
            absP.substringAfter(arxAssetPath)
        } else {
            Noto.warn("File $absP tried to convert to a relative path, but no relative path exists")
            absP
        }
    }

    fun pathsUnder(str: String) : List<String> {
        return File(pickPath(str)).listFiles()?.map { it.path } ?: emptyList()
    }

    fun image(str: String, resizeTo: Int? = null): Image {
        return imageOpt(str, resizeTo) ?: Noto.errAndReturn("Could not find image: $str", SentinelImage)
    }

    fun imageOpt(str: String, resizeTo: Int? = null) : Image? {
        val raw = images.getOrPut(str) {
            val path = pickPath(str)
            if (! File(path).exists()) {
                SentinelImage
            } else {
                val raw = Image.load(path)
                if (resizeTo != null) {
                    if (raw.width <= resizeTo && raw.height <= resizeTo) {
                        raw
                    } else {
                        val newImg = if (raw.width >= raw.height) {
                            Image.downscale(raw, Vec2i(resizeTo, (resizeTo * (raw.height.toFloat() / raw.width.toFloat())).toInt()))
                        } else {
                            Image.downscale(raw, Vec2i((resizeTo * (raw.width.toFloat() / raw.height.toFloat())).toInt(), resizeTo))
                        }
                        raw.destroy()
                        newImg
                    }
                } else {
                    raw
                }
            }
        }
        if (raw == SentinelImage) {
            return null
        } else {
            return raw
        }
    }

    fun amg(str: String) : AsciiCanvas {
        return amgOpt(str) ?: Noto.errAndReturn("Could not find amg $str", SentinelAmgRef.toAmg())
    }

    fun amgOpt(str: String) : AsciiCanvas? {
        val raw = amgs.getOrPut(str) {
            inputStreamOpt(str)?.let { AsciiCanvas.readFrom(it) } ?: SentinelAmgRef.toAmg()
        }
        return if (raw == SentinelAmgRef.toAmg()) {
            null
        } else {
            raw
        }
    }


    fun shader(str: String): Shader = shaders.getOrPut(str) {
        Shader(pickPath("$str.vertex").substringBefore(".vertex"))
    }
    fun typeface(str: String): ArxTypeface = typefaces.getOrPut(str) { ArxTypeface.load(pickPath(str)) }
    fun font(str: String, size: Int): ArxFont {
        return typeface(str).withSize(size)
    }


    object SmlIncluder : ConfigIncluder, ConfigIncluderClasspath {
        override fun withFallback(fallback: ConfigIncluder?): ConfigIncluder? {
            return this
        }

        override fun include(context: ConfigIncludeContext?, what: String?): ConfigObject {
            TODO("Not yet implemented")
        }

        override fun includeResources(context: ConfigIncludeContext, what: String): ConfigObject {
            if (File(pickPath(what)).exists()) {
                return config(what).root()
            } else {
                val inferred = context.relativeTo(what)
                return inferred.parse(context.parseOptions())
            }
        }
    }

    fun config(str: String): Config {
        return configs.getOrPut(str) {
            val f = File(pickPath(str))
            ConfigFactory.parseFile(f, ConfigParseOptions.defaults().appendIncluder(SmlIncluder)).resolve()
        }
    }

    fun configOpt(str: String): Config? {
        val g = configs[str]
        if (g != null) {
            return g
        } else {
            val f = File(pickPath(str))
            if (f.exists()) {
                val c = ConfigFactory.parseFile(f, ConfigParseOptions.defaults().appendIncluder(SmlIncluder)).resolve()
                configs[str] = c

                return c
            } else {
                return null
            }
        }

    }

    fun inputStream(path: String): InputStream = FileInputStream(pickPath(path))

    fun inputStreamOpt(path: String): InputStream? {
        val p = pickPath(path)
        return if (File(p).exists()) {
            FileInputStream(p)
        } else {
            null
        }
    }
}