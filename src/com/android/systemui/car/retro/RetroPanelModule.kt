/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.car.retro

import android.content.Context
import android.view.View
import com.android.car.scalableui.panel.DecorPanelController
import com.android.systemui.car.wm.scalableui.panel.controller.DecorPanelViewMap
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/**
 * Dagger module for the retro overlay pod: the controller factory and its view. Include it in
 * `PanelControllerModule` (see README).
 */
@Module
abstract class RetroPanelModule {
    @Binds
    @IntoMap
    @ClassKey(RetroController::class)
    abstract fun bindRetroControllerFactory(
        factory: RetroController.Factory
    ): DecorPanelController.Factory<*>

    companion object {
        @Provides
        @IntoMap
        @ClassKey(RetroOverlayView::class)
        @DecorPanelViewMap
        fun provideRetroOverlayView(context: Context): View = RetroOverlayView(context)
    }
}
