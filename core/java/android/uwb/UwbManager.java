/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.PersistableBundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * This class provides a way to perform Ultra Wideband (UWB) operations such as querying the
 * device's capabilities and determining the distance and angle between the local device and a
 * remote device.
 *
 * <p>To get a {@link UwbManager}, call the <code>Context.getSystemService(UwbManager.class)</code>.
 *
 * @hide
 */
public final class UwbManager {
    /**
     * Interface for receiving UWB adapter state changes
     */
    public interface AdapterStateCallback {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                STATE_CHANGED_REASON_SESSION_STARTED,
                STATE_CHANGED_REASON_ALL_SESSIONS_CLOSED,
                STATE_CHANGED_REASON_SYSTEM_POLICY,
                STATE_CHANGED_REASON_SYSTEM_BOOT,
                STATE_CHANGED_REASON_ERROR_UNKNOWN})
        @interface StateChangedReason {}

        /**
         * Indicates that the state change was due to opening of first UWB session
         */
        int STATE_CHANGED_REASON_SESSION_STARTED = 0;

        /**
         * Indicates that the state change was due to closure of all UWB sessions
         */
        int STATE_CHANGED_REASON_ALL_SESSIONS_CLOSED = 1;

        /**
         * Indicates that the state change was due to changes in system policy
         */
        int STATE_CHANGED_REASON_SYSTEM_POLICY = 2;

        /**
         * Indicates that the current state is due to a system boot
         */
        int STATE_CHANGED_REASON_SYSTEM_BOOT = 3;

        /**
         * Indicates that the state change was due to some unknown error
         */
        int STATE_CHANGED_REASON_ERROR_UNKNOWN = 4;

        /**
         * Invoked when underlying UWB adapter's state is changed
         * <p>Invoked with the adapter's current state after registering an
         * {@link AdapterStateCallback} using
         * {@link UwbManager#registerAdapterStateCallback(Executor, AdapterStateCallback)}.
         *
         * <p>Possible values for the state to change are
         * {@link #STATE_CHANGED_REASON_SESSION_STARTED},
         * {@link #STATE_CHANGED_REASON_ALL_SESSIONS_CLOSED},
         * {@link #STATE_CHANGED_REASON_SYSTEM_POLICY},
         * {@link #STATE_CHANGED_REASON_SYSTEM_BOOT},
         * {@link #STATE_CHANGED_REASON_ERROR_UNKNOWN}.
         *
         * @param isEnabled true when UWB adapter is enabled, false when it is disabled
         * @param reason the reason for the state change
         */
        void onStateChanged(boolean isEnabled, @StateChangedReason int reason);
    }

    /**
     * Use <code>Context.getSystemService(UwbManager.class)</code> to get an instance.
     */
    private UwbManager() {
        throw new UnsupportedOperationException();
    }
    /**
     * Register an {@link AdapterStateCallback} to listen for UWB adapter state changes
     * <p>The provided callback will be invoked by the given {@link Executor}.
     *
     * <p>When first registering a callback, the callbacks's
     * {@link AdapterStateCallback#onStateChanged(boolean, int)} is immediately invoked to indicate
     * the current state of the underlying UWB adapter with the most recent
     * {@link AdapterStateCallback.StateChangedReason} that caused the change.
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback user implementation of the {@link AdapterStateCallback}
     */
    public void registerAdapterStateCallback(Executor executor, AdapterStateCallback callback) {
        throw new UnsupportedOperationException();
    }

    /**
     * Unregister the specified {@link AdapterStateCallback}
     * <p>The same {@link AdapterStateCallback} object used when calling
     * {@link #registerAdapterStateCallback(Executor, AdapterStateCallback)} must be used.
     *
     * <p>Callbacks are automatically unregistered when application process goes away
     *
     * @param callback user implementation of the {@link AdapterStateCallback}
     */
    public void unregisterAdapterStateCallback(AdapterStateCallback callback) {
        throw new UnsupportedOperationException();
    }

    /**
     * Get a {@link PersistableBundle} with the supported UWB protocols and parameters.
     * <p>The {@link PersistableBundle} should be parsed using a support library
     *
     * <p>Android reserves the '^android.*' namespace</p>
     *
     * @return {@link PersistableBundle} of the device's supported UWB protocols and parameters
     */
    @NonNull
    public PersistableBundle getSpecificationInfo() {
        throw new UnsupportedOperationException();
    }

    /**
     * Check if ranging is supported, regardless of ranging method
     *
     * @return true if ranging is supported
     */
    public boolean isRangingSupported() {
        throw new UnsupportedOperationException();
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            ANGLE_OF_ARRIVAL_SUPPORT_TYPE_NONE,
            ANGLE_OF_ARRIVAL_SUPPORT_TYPE_2D,
            ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_HEMISPHERICAL,
            ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_SPHERICAL})
    public @interface AngleOfArrivalSupportType {}

    /**
     * Indicate absence of support for angle of arrival measurement
     */
    public static final int ANGLE_OF_ARRIVAL_SUPPORT_TYPE_NONE = 1;

    /**
     * Indicate support for planar angle of arrival measurement, due to antenna
     * limitation. Typically requires at least two antennas.
     */
    public static final int ANGLE_OF_ARRIVAL_SUPPORT_TYPE_2D = 2;

    /**
     * Indicate support for three dimensional angle of arrival measurement.
     * Typically requires at least three antennas. However, due to antenna
     * arrangement, a platform may only support hemi-spherical azimuth angles
     * ranging from -pi/2 to pi/2
     */
    public static final int ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_HEMISPHERICAL = 2;

    /**
     * Indicate support for three dimensional angle of arrival measurement.
     * Typically requires at least three antennas. This mode supports full
     * azimuth angles ranging from -pi to pi.
     */
    public static final int ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_SPHERICAL = 3;


    /**
     * Gets the {@link AngleOfArrivalSupportType} supported on this platform
     * <p>Possible return values are
     * {@link #ANGLE_OF_ARRIVAL_SUPPORT_TYPE_NONE},
     * {@link #ANGLE_OF_ARRIVAL_SUPPORT_TYPE_2D},
     * {@link #ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_HEMISPHERICAL},
     * {@link #ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_SPHERICAL}.
     *
     * @return angle of arrival type supported
     */
    @AngleOfArrivalSupportType
    public int getAngleOfArrivalSupport() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get a {@link List} of supported channel numbers based on the device's current location
     * <p>The returned values are ordered by the system's desired ordered of use, with the first
     * entry being the most preferred.
     *
     * <p>Channel numbers are defined based on the IEEE 802.15.4z standard for UWB.
     *
     * @return {@link List} of supported channel numbers ordered by preference
     */
    @NonNull
    public List<Integer> getSupportedChannelNumbers() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get a {@link List} of supported preamble code indices
     * <p> Preamble code indices are defined based on the IEEE 802.15.4z standard for UWB.
     *
     * @return {@link List} of supported preamble code indices
     */
    @NonNull
    public Set<Integer> getSupportedPreambleCodeIndices() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the timestamp resolution for events in nanoseconds
     * <p>This value defines the maximum error of all timestamps for events reported to
     * {@link RangingSession.Callback}.
     *
     * @return the timestamp resolution in nanoseconds
     */
    @SuppressLint("MethodNameUnits")
    public long elapsedRealtimeResolutionNanos() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the number of simultaneous sessions allowed in the system
     *
     * @return the maximum allowed number of simultaneously open {@link RangingSession} instances.
     */
    public int getMaxSimultaneousSessions() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the maximum number of remote devices in a {@link RangingSession} when the local device
     * is the initiator.
     *
     * @return the maximum number of remote devices per {@link RangingSession}
     */
    public int getMaxRemoteDevicesPerInitiatorSession() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the maximum number of remote devices in a {@link RangingSession} when the local device
     * is a responder.
     *
     * @return the maximum number of remote devices per {@link RangingSession}
     */
    public int getMaxRemoteDevicesPerResponderSession() {
        throw new UnsupportedOperationException();
    }
}
