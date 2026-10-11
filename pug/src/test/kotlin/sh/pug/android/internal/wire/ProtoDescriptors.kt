package sh.pug.android.internal.wire

import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FileDescriptor

/** Descriptors for the vendored protos, from the `buf build` output `make protos` writes to test resources. */
internal object ProtoDescriptors {
    private val files: Collection<FileDescriptor> by lazy { load() }

    fun message(fullName: String): Descriptor {
        val packageName = fullName.substringBeforeLast('.')
        val simpleName = fullName.substringAfterLast('.')
        return files.firstNotNullOfOrNull { file ->
            if (file.`package` == packageName) file.findMessageTypeByName(simpleName) else null
        } ?: error("no message $fullName in pug.binpb")
    }

    fun messages(packageName: String): List<Descriptor> =
        files.filter { it.`package` == packageName }.flatMap { it.messageTypes }

    private fun load(): Collection<FileDescriptor> {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/pug.binpb")) {
            "pug.binpb missing; run make protos"
        }.use { it.readBytes() }
        val protos = FileDescriptorSet.parseFrom(bytes).fileList.associateBy { it.name }
        val built = LinkedHashMap<String, FileDescriptor>()

        fun build(name: String): FileDescriptor = built[name] ?: run {
            val proto = protos.getValue(name)
            val dependencies = proto.dependencyList.map { build(it) }.toTypedArray()
            FileDescriptor.buildFrom(proto, dependencies).also { built[name] = it }
        }

        protos.keys.forEach { build(it) }
        return built.values
    }
}
