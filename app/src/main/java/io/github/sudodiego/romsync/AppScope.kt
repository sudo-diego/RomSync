package io.github.sudodiego.romsync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Para operaciones que no se pueden cortar a la mitad (borrar, mover BIOS, respaldar, restaurar). Con el scope de
 * una ventana, si la ventana desaparece (porque cambió la lista que la mostraba) la operación se cancelaba con
 * "The coroutine scope left the composition".
 */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
