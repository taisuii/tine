package com.android.tine.module;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;

import com.android.bridge.ILoadPackageHook;
import com.android.bridge.IZygoteInitHook;
import com.android.bridge.IModuleHook;
import com.android.bridge.TsBridge;
import com.android.bridge.TsBridge.CopyOnWriteSortedSet;
import com.android.bridge.callbacks.TsLoadPackage;

public final class TineModule {
    public static final String TAG = "TineModule";
    public static boolean disableHooks = false;
    public static boolean disableZygoteInitCallbacks = false;
    private static ExtHandler sExtHandler;

    public static ExtHandler getExtHandler() {
        return sExtHandler;
    }

    public static void setExtHandler(ExtHandler n) {
        sExtHandler = n;
    }

    private static final CopyOnWriteSortedSet<TsLoadPackage> sLoadedPackageCallbacks = new CopyOnWriteSortedSet<>();

    private TineModule() {
    }

    public static void loadModule(String module) {
        loadModule(new File(module));
    }

    public static void loadModule(File module) {
        loadModule(module, false);
    }

    public static void loadModule(File module, boolean startsSystemServer) {
        loadModule(module, null, startsSystemServer);
    }

    public static void loadModule(File module, String librarySearchPath, boolean startsSystemServer) {
        if (!module.exists()) {
            Log.e(TAG, "  File " + module + " does not exist");
            return;
        }
        ClassLoader initCl = TineModule.class.getClassLoader();
        String modulePath = module.getAbsolutePath();
        ModuleClassLoader mcl = new ModuleClassLoader(modulePath, librarySearchPath, initCl);
        loadOpenedModule(modulePath, mcl, startsSystemServer);
    }

    public static void loadOpenedModule(String modulePath, ClassLoader mcl, boolean startsSystemServer) {
        if (!checkModule(mcl)) return;
        InputStream initIs = openModuleInitStream(mcl);
        if (initIs == null) {
            Log.e(TAG, "  Failed to load module " + modulePath);
            Log.e(TAG, "  assets/module_init not found in the module APK");
            return;
        }

        BufferedReader initReader = new BufferedReader(new InputStreamReader(initIs));
        try {
            String className;
            while ((className = initReader.readLine()) != null) {
                className = className.trim();
                if (className.isEmpty() || className.startsWith("#"))
                    continue;

                try {
                    Class<?> c = mcl.loadClass(className);

                    if (!IModuleHook.class.isAssignableFrom(c)) {
                        Log.e(TAG, "    Cannot load callback class " + className + " in module " + modulePath + " :");
                        Log.e(TAG, "    This class doesn't implement any sub-interface of IModuleHook, skipping it");
                        continue;
                    }

                    IModuleHook callback = (IModuleHook) c.newInstance();

                    if (callback instanceof IZygoteInitHook && !disableZygoteInitCallbacks) {
                        IZygoteInitHook.StartupParam param = new IZygoteInitHook.StartupParam();
                        param.modulePath = modulePath;
                        param.startsSystemServer = startsSystemServer;
                        ((IZygoteInitHook) callback).initZygote(param);
                    }

                    if (callback instanceof ILoadPackageHook)
                        hookLoadPackage((ILoadPackageHook) callback);

                    ExtHandler extHandler = sExtHandler;
                    if (extHandler != null)
                        extHandler.handle(callback);
                } catch (Throwable e) {
                    Log.e(TAG, "    Failed to load class " + className + " from module " + modulePath + " :", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "  Failed to load module " + modulePath);
            Log.e(TAG, "  Cannot read module init list in the module APK", e);
        } finally {
            closeQuietly(initReader);
        }
    }

    private static InputStream openModuleInitStream(ClassLoader mcl) {
        final String[] candidates = {"assets/module_init"};
        for (String filename : candidates) {
            try {
                if (mcl instanceof ModuleClassLoader) {
                    URL url = ((ModuleClassLoader) mcl).findResource(filename);
                    if (url != null) {
                        return url.openStream();
                    }
                } else {
                    InputStream is = mcl.getResourceAsStream(filename);
                    if (is != null) {
                        return is;
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "  Cannot open " + filename, e);
            }
        }
        return null;
    }

    public static boolean checkModule(ClassLoader mcl) {
        boolean fastPath = mcl instanceof ModuleClassLoader;
        try {
            String name = "com.android.tools.fd.runtime.BootstrapApplication";
            Class<?> cls = fastPath ? ((ModuleClassLoader) mcl).findClass(name) : mcl.loadClass(name);
            if (cls != null) {
                Log.e(TAG, "  Cannot load module, please disable \"Instant Run\" in Android Studio.");
                return false;
            }
        } catch (ClassNotFoundException ignored) {
        }

        boolean conflict;
        if (fastPath) {
            try {
                conflict = ((ModuleClassLoader) mcl).findClass(TsBridge.class.getName()) != null;
            } catch (ClassNotFoundException ignored) {
                conflict = false;
            }
        } else {
            try {
                conflict = mcl.loadClass(TsBridge.class.getName()) != TsBridge.class;
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "  Cannot load module, TsBridge is not available on the class loader", e);
                Log.e(TAG, "  Make sure you have set parent of the class loader");
                return false;
            }
        }
        if (conflict) {
            Log.e(TAG, "  Cannot load module:");
            Log.e(TAG, "  The bridge API classes are compiled into the module's APK.");
            Log.e(TAG, "  This may cause strange issues and must be fixed by the module developer.");
            return false;
        }
        return true;
    }

    public static void hookLoadPackage(ILoadPackageHook callback) {
        sLoadedPackageCallbacks.add(new TsLoadPackage.Wrapper(callback));
    }

    public static void onPackageLoad(String packageName, String processName, ApplicationInfo appInfo,
                                     boolean isFirstApp, ClassLoader classLoader) {
        TsLoadPackage.LoadPackageParam param = new TsLoadPackage.LoadPackageParam(sLoadedPackageCallbacks);
        param.packageName = packageName;
        param.processName = processName;
        param.appInfo = appInfo;
        param.isFirstApplication = isFirstApp;
        param.classLoader = classLoader;
        TsLoadPackage.callAll(param);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null)
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
    }

    public interface ExtHandler {
        void handle(IModuleHook callback);
    }
}
