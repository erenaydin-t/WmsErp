package com.wmserp.app.di

import com.wmserp.app.core.scanner.HardwareScannerManager
import com.wmserp.app.core.scanner.ScanEventBus
import com.wmserp.app.core.scanner.ScannerController
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideScanEventBus(): ScanEventBus = ScanEventBus()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ScannerBindingModule {
    @Binds
    @Singleton
    abstract fun bindScannerController(manager: HardwareScannerManager): ScannerController
}
