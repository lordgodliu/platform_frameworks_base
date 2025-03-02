/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.os;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Trace;

import dalvik.system.DelegateLastClassLoader;
import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

import java.util.List;

/**
 * Creates class loaders.
 *
 * @hide
 */
public class ClassLoaderFactory {
    // Unconstructable
    private ClassLoaderFactory() {}

    private static final String PATH_CLASS_LOADER_NAME = PathClassLoader.class.getName();
    private static final String DEX_CLASS_LOADER_NAME = DexClassLoader.class.getName();
    private static final String DELEGATE_LAST_CLASS_LOADER_NAME =
            DelegateLastClassLoader.class.getName();

    /**
     * Returns the name of the class for PathClassLoader.
     */
    public static String getPathClassLoaderName() {
        return PATH_CLASS_LOADER_NAME;
    }

    /**
     * Returns true if {@code name} is a supported classloader. {@code name} must be a
     * binary name of a class, as defined by {@code Class.getName}.
     */
    public static boolean isValidClassLoaderName(String name) {
        // This method is used to parse package data and does not accept null names.
        return name != null && (isPathClassLoaderName(name) || isDelegateLastClassLoaderName(name));
    }

    /**
     * Returns true if {@code name} is the encoding for either PathClassLoader or DexClassLoader.
     * The two class loaders are grouped together because they have the same behaviour.
     */
    public static boolean isPathClassLoaderName(String name) {
        // For null values we default to PathClassLoader. This cover the case when packages
        // don't specify any value for their class loaders.
        return name == null || PATH_CLASS_LOADER_NAME.equals(name) ||
                DEX_CLASS_LOADER_NAME.equals(name);
    }

    /**
     * Returns true if {@code name} is the encoding for the DelegateLastClassLoader.
     */
    public static boolean isDelegateLastClassLoaderName(String name) {
        return DELEGATE_LAST_CLASS_LOADER_NAME.equals(name);
    }

    /**
     * Same as {@code createClassLoader} below, except that no associated namespace
     * is created.
     */
    public static ClassLoader createClassLoader(String dexPath,
            String librarySearchPath, ClassLoader parent, String classloaderName,
            List<ClassLoader> sharedLibraries) {
        ClassLoader[] arrayOfSharedLibraries = (sharedLibraries == null)
                ? null
                : sharedLibraries.toArray(new ClassLoader[sharedLibraries.size()]);
        if (isPathClassLoaderName(classloaderName)) {
            return new PathClassLoader(dexPath, librarySearchPath, parent, arrayOfSharedLibraries);
        } else if (isDelegateLastClassLoaderName(classloaderName)) {
            return new DelegateLastClassLoader(dexPath, librarySearchPath, parent,
                    arrayOfSharedLibraries);
        }

        throw new AssertionError("Invalid classLoaderName: " + classloaderName);
    }

    /**
     * Same as {@code createClassLoader} below, but passes a null list of shared
     * libraries.
     */
    public static ClassLoader createClassLoader(String dexPath,
            String librarySearchPath, String libraryPermittedPath, ClassLoader parent,
            int targetSdkVersion, boolean isNamespaceShared, String classLoaderName) {
        return createClassLoader(dexPath, librarySearchPath, libraryPermittedPath,
            parent, targetSdkVersion, isNamespaceShared, classLoaderName, null);
    }


    /**
     * Create a ClassLoader and initialize a linker-namespace for it.
     */
    public static ClassLoader createClassLoader(String dexPath,
            String librarySearchPath, String libraryPermittedPath, ClassLoader parent,
            int targetSdkVersion, boolean isNamespaceShared, String classLoaderName,
            List<ClassLoader> sharedLibraries) {

        final ClassLoader classLoader = createClassLoader(dexPath, librarySearchPath, parent,
                classLoaderName, sharedLibraries);

        // TODO(b/142191088) merge 6a5b8b1f6db172b5aaadcec0c3868e54e214b675
        String sonameList = "ALL";

        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "createClassloaderNamespace");
        String errorMessage = createClassloaderNamespace(classLoader,
                                                         targetSdkVersion,
                                                         librarySearchPath,
                                                         libraryPermittedPath,
                                                         isNamespaceShared,
                                                         dexPath,
                                                         sonameList);
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

        if (errorMessage != null) {
            throw new UnsatisfiedLinkError("Unable to create namespace for the classloader " +
                                           classLoader + ": " + errorMessage);
        }

        return classLoader;
    }

    @UnsupportedAppUsage
    private static native String createClassloaderNamespace(ClassLoader classLoader,
                                                            int targetSdkVersion,
                                                            String librarySearchPath,
                                                            String libraryPermittedPath,
                                                            boolean isNamespaceShared,
                                                            String dexPath,
                                                            String sonameList);
}
