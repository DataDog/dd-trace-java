import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty

abstract class InstrumentExtension {
  abstract fun getPlugins(): ListProperty<String>
  var additionalClasspath: Map<String /* taskName */, List<DirectoryProperty>> = mutableMapOf()
}
