package androidx.lifecycle.viewmodel
abstract class CreationExtras {
    class Key<T>
    abstract operator fun <T> get(key: Key<T>): T?
}
