package androidx.lifecycle
import android.app.Application
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

abstract class ViewModel { open fun onCleared() {} }
val ViewModel.viewModelScope: CoroutineScope get() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
class ViewModelProvider {
    interface Factory { fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T }
    object AndroidViewModelFactory { val APPLICATION_KEY: CreationExtras.Key<Application> = CreationExtras.Key() }
}
