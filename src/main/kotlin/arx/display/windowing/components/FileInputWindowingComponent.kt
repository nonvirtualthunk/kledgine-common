package arx.display.windowing.components

import arx.core.*
import arx.display.windowing.*
import arx.engine.*
import com.typesafe.config.ConfigValue
import org.lwjgl.system.MemoryUtil.memAllocPointer
import org.lwjgl.util.nfd.NativeFileDialog
import java.io.File
import kotlin.reflect.typeOf


data class FileInputChosen(val file : File, val src : DisplayEvent) : WidgetEvent(src)

enum class FileInputKind {
    Open,
    Save,
    Directory;

    companion object : FromConfigCreator<FileInputKind> {
        override fun createFromConfig(cv: ConfigValue?): FileInputKind? {
            return cv?.asStr()?.let {
                when(it.lowercase()) {
                    "open" -> Open
                    "save" -> Save
                    "directory" -> Directory
                    else -> null
                }
            }
        }
    }
}

data class FileInput(
    var file: File? = null,
    var textBinding: PropertyBinding? = null,
    internal var onChangeSet : (File) -> Unit = { _ -> },
    var kind : FileInputKind = FileInputKind.Open,
    var fileFilters : List<String> = emptyList(),
    var changeSignal: Bindable<Any?> = ValueBindable.Null()
) : DisplayData {
    companion object : DataType<FileInput>(FileInput(),sparse = true), FromConfigCreator<FileInput> {
        override fun createFromConfig(cv: ConfigValue?): FileInput? {
            return if (cv["type"].asStr()?.lowercase() == "fileinput") {
                var filePath : String? = null
                var bindingPattern : String? = null
                cv["filePath"].asStr()?.let { t ->
                    stringBindingPattern.match(t).ifLet { (pattern) ->
                        bindingPattern = pattern
                    }.orElse {
                        filePath = t
                    }
                }

                val twoWayBinding = cv["twoWayBinding"].asBool() ?: false

                FileInput(
                    file = filePath?.let { File(it) },
                    textBinding = bindingPattern?.let { PropertyBinding(it, twoWayBinding) },
                    kind = FileInputKind(cv["fileInputKind"]) ?: FileInputKind.Open,
                    fileFilters = cv["fileFilters"]?.asList()?.mapNotNull { it.asStr() } ?: emptyList(),
                    changeSignal = bindableAnyOpt(cv["changeSignal"])
                )
            } else {
                null
            }
        }
    }

    override fun dataType(): DataType<*> {
        return FileInput
    }

    fun copy() : FileInput {
        return copy(changeSignal = changeSignal.copyBindable())
    }
}

object FileInputWindowingComponent : WindowingComponent {

    override fun dataTypes() : List<DataType<EntityData>> {
        return listOf(FileInput)
    }

    override fun initializeWidget(w: Widget) {
        if (w[FileInput] != null && w[TextDisplay] == null) {
            w.attachData(TextDisplay())
        }
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val fi = w[FileInput] ?: return
        val td = w[TextDisplay] ?: return

        fi.textBinding?.let { binding ->
            binding.update(ctx, { _, bound ->
                if (fi.file == null && bound != null) {
                    when(bound) {
                        is File -> fi.file = bound
                        is String -> fi.file = File(bound)
                        else -> {
                            if (w.showing()) {
                                Noto.warn("Bound value for file input is not a file / path")
                            }
                        }
                    }
                    syncDisplayToData(w, fi, td)
                }
            }, { paramType, setter ->
                when (paramType) {
                    typeOf<File?>() -> {
                        fi.onChangeSet = { file -> setter(file) }
                    }
                    typeOf<File>() -> {
                        fi.onChangeSet = { file -> setter(file) }
                    }
                    else -> {
                        if (w.showing()) {
                            Noto.warn("unsupported type for two way binding on file input : $paramType")
                        }
                    }
                }

                fi.file?.let {
                    fi.onChangeSet(it)
                }
            })
        }
    }

    fun syncDisplayToData(w: Widget, fi: FileInput, td: TextDisplay) {
        val transformed = fi.file.ifLet {
            val fileName = it.path.substringAfterLast('/')
            val str = if (fileName.length > 20) {
                fileName.substring(0 until 8) + "..." + fileName.substring(fileName.length - 9)
            } else {
                fileName
            }
            RichText(str)
        }.orElse {
            RichText("<file>")
        }

        if (transformed != td.text()) {
            td.text = bindable(transformed)
            w.markForUpdate(RecalculationFlag.Contents)
            if (w.width.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsX)
            }
            if (w.height.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsY)
            }
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val fi = w[FileInput] ?: return false
        val td = w[TextDisplay] ?: return false

        return when(event) {
            is WidgetMouseReleaseEvent -> {
                val outPath = memAllocPointer(1)

                val filterStr = fi.fileFilters.joinToString(";")
                val defaultPath = fi.file?.absolutePath
                val result = when(fi.kind) {
                    FileInputKind.Open -> NativeFileDialog.NFD_OpenDialog(filterStr, defaultPath, outPath)
                    FileInputKind.Save -> NativeFileDialog.NFD_SaveDialog(filterStr, defaultPath, outPath)
                    FileInputKind.Directory -> NativeFileDialog.NFD_PickFolder(defaultPath, outPath)
                }

                when(result) {
                    NativeFileDialog.NFD_OKAY -> {
                        val pathStr = outPath.getStringUTF8(0)
                        NativeFileDialog.nNFD_Free(outPath.get(0))

                        val file = File(pathStr)
                        fi.file = file
                        fi.onChangeSet(file)

                        w.windowingSystem.fireEvent(FileInputChosen(file, event).withWidget(w))
                        fi.changeSignal()?.let { s -> w.fireEvent(SignalEvent(s, w.data())) }

                        syncDisplayToData(w, fi, td)
                    }
                    NativeFileDialog.NFD_CANCEL -> {
                        println("File input cancelled")
                    }
                    NativeFileDialog.NFD_ERROR -> {
                        System.err.println("Error encountered in file input")
                    }
                }

                true
            }
            else -> false
        }
    }
}


fun fileDialog(kind: FileInputKind, defaultPath: String, filterStr : String = "") : File? {
    val outPath = memAllocPointer(1)

    val result = when(kind) {
        FileInputKind.Open -> NativeFileDialog.NFD_OpenDialog(filterStr, defaultPath, outPath)
        FileInputKind.Save -> NativeFileDialog.NFD_SaveDialog(filterStr, defaultPath, outPath)
        FileInputKind.Directory -> NativeFileDialog.NFD_PickFolder(defaultPath, outPath)
    }

    return when(result) {
        NativeFileDialog.NFD_OKAY -> {
            val pathStr = outPath.getStringUTF8(0)
            NativeFileDialog.nNFD_Free(outPath.get(0))

            File(pathStr)
        }
        NativeFileDialog.NFD_CANCEL -> {
            null
        }
        NativeFileDialog.NFD_ERROR -> {
            System.err.println("Error encountered in file input")
            null
        }
        else -> {
            System.err.println("Invalid file dialog state: $result")
            null
        }
    }

}