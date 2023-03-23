/*
 * Copyright (C) 2022 The Android Open Source Project
 *
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

package com.android.permissioncontroller.permission.data

import android.app.Application
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.util.Log
import com.android.modules.utils.build.SdkLevel
import com.android.permission.safetylabel.SafetyLabel
import com.android.permissioncontroller.PermissionControllerApplication
import com.android.permissioncontroller.permission.model.livedatatypes.SafetyLabelInfo
import kotlinx.coroutines.Job

/**
 * [SafetyLabelInfo] [LiveData] for the specified package
 *
 * @param app current Application
 * @param packageName name of the package to get SafetyLabel information for
 * @param user The user of the package
 */
class SafetyLabelInfoLiveData
private constructor(
    private val app: Application,
    private val packageName: String,
    private val user: UserHandle
) :
    SmartAsyncMediatorLiveData<SafetyLabelInfo>(),
    PackageBroadcastReceiver.PackageBroadcastListener {

    private val lightInstallSourceInfoLiveData = LightInstallSourceInfoLiveData[packageName, user]

    init {
        addSource(lightInstallSourceInfoLiveData) { update() }

        update()
    }

    override fun onActive() {
        super.onActive()
        PackageBroadcastReceiver.addChangeCallback(packageName, this)
    }

    override fun onInactive() {
        super.onInactive()
        PackageBroadcastReceiver.removeChangeCallback(packageName, this)
    }

    /**
     * Callback from the PackageBroadcastReceiver
     *
     * @param packageName the name of the package which was updated.
     */
    override fun onPackageUpdate(packageName: String) {
        update()
    }

    override suspend fun loadDataAndPostValue(job: Job) {
        if (job.isCancelled) {
            return
        }

        if (lightInstallSourceInfoLiveData.isStale) {
            return
        }

        if (!SdkLevel.isAtLeastU()) {
            postValue(SafetyLabelInfo.UNAVAILABLE)
            return
        }

        // TODO(b/261607291): Add support for preinstall apps that provide SafetyLabel. Initiating
        //  package is null until updated from an app store
        val installSourcePackageName = lightInstallSourceInfoLiveData.value?.initiatingPackageName
        if (installSourcePackageName == null) {
            postValue(SafetyLabelInfo.UNAVAILABLE)
            return
        }

        val safetyLabelInfo: SafetyLabelInfo =
            try {
                val safetyLabel: SafetyLabel? = getSafetyLabel(packageName, user)
                if (safetyLabel != null) {
                    SafetyLabelInfo(safetyLabel, installSourcePackageName)
                } else {
                    SafetyLabelInfo.UNAVAILABLE
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(LOG_TAG, "SafetyLabel for $packageName not found")
                invalidateSingle(packageName to user)
                SafetyLabelInfo.UNAVAILABLE
            }
        postValue(safetyLabelInfo)
    }

    /** Returns the [SafetyLabel] for the given package and user. */
    @Throws(PackageManager.NameNotFoundException::class)
    private fun getSafetyLabel(packageName: String, user: UserHandle): SafetyLabel? {
        val userContext =
            if (user == Process.myUserHandle()) {
                app
            } else {
                app.createContextAsUser(user, /* flags= */ 0)
            }

        return SafetyLabel.getSafetyLabelFromMetadata(
            userContext.packageManager.getAppMetadata(packageName))
    }

    companion object :
        DataRepositoryForPackage<Pair<String, UserHandle>, SafetyLabelInfoLiveData>() {
        private val LOG_TAG = SafetyLabelInfoLiveData::class.java.simpleName

        override fun newValue(key: Pair<String, UserHandle>): SafetyLabelInfoLiveData {
            return SafetyLabelInfoLiveData(
                PermissionControllerApplication.get(), key.first, key.second)
        }
    }
}
